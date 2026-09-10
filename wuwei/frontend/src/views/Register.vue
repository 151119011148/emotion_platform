<template>
  <div style="height: 100%; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1f2d3d 0%, #2b4a6f 100%)">
    <el-card style="width: 380px" shadow="always">
      <div style="font-size: 20px; font-weight: 700; text-align: center; margin-bottom: 20px">注册账号</div>
      <el-form :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.nickname" placeholder="昵称（可选）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">注 册</el-button>
        <div style="text-align: center; margin-top: 12px">
          <el-button link type="primary" @click="$router.push('/login')">已有账号，去登录</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const form = reactive({ username: '', password: '', nickname: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await userStore.register(form)
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>
