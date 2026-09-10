<template>
  <div style="height: 100%; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1f2d3d 0%, #2b4a6f 100%)">
    <el-card style="width: 380px" shadow="always">
      <div style="text-align: center; margin-bottom: 20px">
        <div style="font-size: 20px; font-weight: 700">五维情绪导航系统</div>
        <div class="muted" style="margin-top: 6px">大盘生态 · 主线 · 连板 · 首板 · 阵眼</div>
      </div>
      <el-form :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="Lock" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">登 录</el-button>
        <div style="text-align: center; margin-top: 12px">
          <span class="muted">演示账号 demo / demo123</span>
          <el-button link type="primary" @click="$router.push('/register')">注册</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(form)
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>
