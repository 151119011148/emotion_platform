<template>
  <div class="admin-page">
    <div class="page-head">
      <div>
        <h2>账号管理</h2>
        <p class="sub">
          账号由超级管理员统一开通，不再开放自助注册。「强制下线」只作废对方已签发的令牌，
          账号与密码都不动；改角色或重置密码会自动让对方重新登录。
        </p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon class="btn-icon"><Plus /></el-icon>新增账号
      </el-button>
    </div>

    <el-alert v-if="!userStore.isSuperAdmin" class="deny" type="warning" :closable="false" show-icon
      title="你不是超级管理员，无法查看或操作账号" />

    <el-table v-else v-loading="loading" :data="users" stripe class="tbl">
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="nickname" label="昵称" min-width="140" />
      <el-table-column label="角色" width="130">
        <template #default="{ row }">
          <el-tag :type="row.role === 'SUPER_ADMIN' ? 'danger' : 'info'" size="small">
            {{ row.roleLabel }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" size="small" @click="kick(row)">强制下线</el-button>
          <el-button link type="danger" size="small" :disabled="row.id === myId" @click="remove(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span class="empty">还没有任何账号</span>
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑账号' : '新增账号'" width="440px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!editing" placeholder="3-50 个字符" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="不填则与用户名相同" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password
            :placeholder="editing ? '留空表示不修改密码' : '至少 6 个字符'" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="普通用户" value="USER" />
            <el-option label="超级管理员" value="SUPER_ADMIN" />
          </el-select>
        </el-form-item>
        <div v-if="editing" class="hint">保存后该账号会被强制重新登录一次。</div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api/modules'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const users = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref(null)

// 自己的 id 要从库里的账号行反查（登录态里只有 username），用来禁掉「删自己」。
// 拦在这里而不是等后端报错：后端当然也拦，但那要等一次往返才知道按钮不该点。
const myId = computed(() => {
  const hit = users.value.find(u => u.username === userStore.username)
  return hit ? hit.id : null
})

const form = reactive({ username: '', nickname: '', password: '', role: 'USER' })
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '3-50 个字符', trigger: 'blur' }
  ],
  password: [
    { min: 6, max: 100, message: '至少 6 个字符', trigger: 'blur' }
  ]
}

async function load() {
  if (!userStore.isSuperAdmin) return
  loading.value = true
  try {
    const res = await adminApi.users()
    users.value = res.data || []
  } catch (e) {
    users.value = []
  } finally {
    loading.value = false
  }
}

function blankForm() {
  form.username = ''
  form.nickname = ''
  form.password = ''
  form.role = 'USER'
}

function openCreate() {
  editing.value = null
  blankForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  form.username = row.username || ''
  form.nickname = row.nickname || ''
  form.password = ''
  form.role = row.role || 'USER'
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (!editing.value && (!form.password || form.password.length < 6)) {
    ElMessage.error('新增账号必须设置至少 6 位的密码')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      // 三个字段各自独立可空：null = 这一项不改，只有传了值才动
      await adminApi.updateUser(editing.value.id, {
        nickname: form.nickname || null,
        password: form.password || null,
        role: form.role
      })
      ElMessage.success('已保存，该账号需重新登录')
    } else {
      await adminApi.createUser({
        username: form.username,
        nickname: form.nickname || null,
        password: form.password,
        role: form.role
      })
      ElMessage.success('账号已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function kick(row) {
  try {
    await ElMessageBox.confirm(
      `确定让「${row.nickname || row.username}」已登录的会话全部失效？对方需要重新登录，账号本身不受影响。`,
      '强制下线',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await adminApi.kickUser(row.id)
    ElMessage.success('已强制下线')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除账号「${row.nickname || row.username}」？该账号将立即无法登录，此操作不可撤销。`,
      '删除账号',
      { type: 'error' }
    )
  } catch (e) {
    return
  }
  try {
    await adminApi.deleteUser(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除失败')
  }
}

function fmt(v) {
  if (!v) return '—'
  // 后端给的是 LocalDateTime 序列化结果（"2026-09-30T11:32:06" 或数组），只取到分钟
  const s = String(v).replace('T', ' ').slice(0, 16)
  return s || '—'
}

onMounted(load)
</script>

<style scoped>
.admin-page {
  max-width: 1100px;
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 20px;
}
.page-head h2 {
  margin: 0 0 6px;
  color: #e1e8ed;
  font-size: 20px;
}
.sub {
  margin: 0;
  color: #8899a6;
  font-size: 13px;
  line-height: 1.7;
  max-width: 720px;
}
.btn-icon {
  margin-right: 4px;
}
/* 深色底上 el-table 的斑马纹走 --el-fill-color-lighter，已在 App.vue 全站压过 */
.tbl {
  margin-top: 4px;
}
.deny {
  margin-top: 8px;
}
.empty {
  color: #8899a6;
  font-size: 13px;
}
.hint {
  color: #8899a6;
  font-size: 12px;
  padding-left: 90px;
}
</style>
