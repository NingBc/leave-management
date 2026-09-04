<template>
  <el-dialog
    :model-value="modelValue"
    title="修改密码"
    :width="isMobile ? '92%' : '420px'"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="resetForm"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :label-position="isMobile ? 'top' : 'right'"
      label-width="90px"
    >
      <el-form-item label="当前密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { useBreakpoint } from '../composables/useBreakpoint'

defineProps({
  modelValue: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'success'])

const { isMobile } = useBreakpoint()
const formRef = ref(null)
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const rules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.newPassword) callback(new Error('两次输入的新密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

const resetForm = () => {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

const submit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      saving.value = true
      await request.post('/system/user/change-password', {
        oldPassword: form.oldPassword,
        newPassword: form.newPassword,
        confirmPassword: form.confirmPassword
      })
      ElMessage.success('密码已修改，请用新密码重新登录')
      emit('update:modelValue', false)
      emit('success')
    } catch (e) {
      console.error(e)
    } finally {
      saving.value = false
    }
  })
}
</script>
