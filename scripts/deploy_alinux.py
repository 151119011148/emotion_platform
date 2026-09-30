# -*- coding: utf-8 -*-
"""
emotion 发版到阿里云（本机没有 expect，用 paramiko 顶替 deploy/alinux/scripts/*.exp）。

用法（密码从环境变量进，不落盘、不进命令行历史）：
  PYTHONIOENCODING=utf-8 python scripts/deploy_alinux.py --host 47.117.110.143 --user root
  SSH 密码从环境变量 EMOTION_SSH_PASS 读；没有则交互式提示（getpass，不回显）。

做什么：
  ① 上传 emotion-server.jar（先 .new 后 mv，避免截断运行中进程正在用的 jar）
  ② 上传前端 dist（打包成 tgz 再解开）
  ③ systemctl restart emotion-server
  ④ 健康检查（首页 200 + /api/auth/login 探活）

刻意不做（与 deploy.sh 的差异，本次发版无表结构/配置变更）：
  · 不跑 init_full.sql（它对每张表 DROP TABLE IF EXISTS，会把线上数据退回快照时刻）
  · 不覆盖远端 application-local.yml（原脚本每次重生成 JWT，会让线上已登录用户全部掉线）
"""
import argparse
import getpass
import os
import sys
import tarfile
import time

import paramiko

import glob

LOCAL = r'D:\gaofeng\emotion_platform'
DIST = os.path.join(LOCAL, 'emotion-web', 'dist')
REMOTE_APP = '/opt/emotion/app'
REMOTE_WEB = '/var/www/emotion-web'


def find_jar():
    """版本号会变（当前是 emotion-server-1.0.0.jar），别写死；排除 repackage 前的 .original。"""
    cands = [p for p in glob.glob(os.path.join(LOCAL, 'emotion-server', 'target', 'emotion-server-*.jar'))
             if not p.endswith('.original')]
    if not cands:
        return None
    return max(cands, key=os.path.getmtime)


def connect(host, user, pwd):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(host, username=user, password=pwd, timeout=20, allow_agent=False, look_for_keys=False)
    return c


def run(c, cmd, desc=''):
    print('  $ ' + (desc or cmd[:110]))
    _, out, err = c.exec_command(cmd, timeout=600)
    code = out.channel.recv_exit_status()
    o = out.read().decode('utf-8', 'replace').strip()
    e = err.read().decode('utf-8', 'replace').strip()
    if o:
        print('    | ' + o.replace('\n', '\n    | '))
    if e:
        print('    ! ' + e.replace('\n', '\n    ! '))
    if code != 0:
        sys.exit('远端命令失败(%d)：%s' % (code, cmd))
    return o


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default=os.environ.get('ALINUX_HOST', '47.117.110.143'))
    ap.add_argument('--user', default=os.environ.get('ALINUX_SSH_USER', 'root'))
    ap.add_argument('--skip-backend', action='store_true')
    ap.add_argument('--skip-frontend', action='store_true')
    a = ap.parse_args()

    jar = find_jar()
    if not jar:
        sys.exit('本地 jar 不存在（先跑 mvn -DskipTests clean package）')
    if not a.skip_frontend and not os.path.isdir(DIST):
        sys.exit('本地 dist 不存在：%s（先跑 vite build）' % DIST)

    pwd = os.environ.get('EMOTION_SSH_PASS') or getpass.getpass('服务器 %s@%s 密码：' % (a.user, a.host))

    c = connect(a.host, a.user, pwd)
    sftp = c.open_sftp()
    try:
        if not a.skip_backend:
            print('① 上传后端 jar → %s/emotion-server.jar（本地 %s）' % (REMOTE_APP, os.path.basename(jar)))
            sftp.put(jar, REMOTE_APP + '/emotion-server.jar.new')
            run(c, 'mv -f %s/emotion-server.jar.new %s/emotion-server.jar && ls -la %s/'
                % (REMOTE_APP, REMOTE_APP, REMOTE_APP), '换名生效')

        if not a.skip_frontend:
            print('② 打包并上传前端 dist → %s' % REMOTE_WEB)
            tgz = os.path.join(LOCAL, 'scripts', '_dist.tgz')
            with tarfile.open(tgz, 'w:gz') as t:
                t.add(DIST, arcname='.')
            sftp.put(tgz, '/tmp/dist.tgz')
            run(c, 'mkdir -p %s && cd %s && rm -rf ./* && tar -xzf /tmp/dist.tgz && echo WEB_UNPACKED'
                % (REMOTE_WEB, REMOTE_WEB), '解包 dist')

        print('③ 重启后端')
        run(c, 'systemctl restart emotion-server && sleep 2 && systemctl is-active emotion-server',
            'restart + 看 active 状态')

        print('④ 健康检查')
        time.sleep(8)
        run(c, 'curl -s -o /dev/null -m 5 -w "首页 HTTP=%{http_code}\\n" http://127.0.0.1/ ; '
               'curl -s -m 5 -X POST http://127.0.0.1/api/auth/login -H "Content-Type: application/json" '
               '-d \'{"username":"__probe__","password":"__probe__"}\' ; echo',
            '本地探活（无效凭据应返回业务错误码）')
        run(c, 'journalctl -u emotion-server -n 25 --no-pager | tail -25', '后端最后 25 行日志')
    finally:
        sftp.close()
        c.close()
    print('发版完成 ✔')


if __name__ == '__main__':
    main()
