<template>
  <div class="cron-generator">
    <el-form :inline="true" size="small">
      <el-form-item label="执行周期">
        <el-select v-model="frequencyType" @change="handleFrequencyChange" style="width: 120px">
          <el-option label="每天" value="daily" />
          <el-option label="每周" value="weekly" />
          <el-option label="每月" value="monthly" />
          <el-option label="自定义" value="custom" />
        </el-select>
      </el-form-item>

      <!-- 每天：选择时间 -->
      <el-form-item v-if="frequencyType === 'daily'" label="执行时间">
        <el-time-picker
          v-model="dailyTime"
          format="HH:mm"
          value-format="HH:mm"
          placeholder="选择时间"
          @change="generateCron"
        />
      </el-form-item>

      <!-- 每周：选择星期几和时间 -->
      <template v-if="frequencyType === 'weekly'">
        <el-form-item label="星期">
          <el-select v-model="weekDay" @change="generateCron" style="width: 100px">
            <el-option label="周一" value="MON" />
            <el-option label="周二" value="TUE" />
            <el-option label="周三" value="WED" />
            <el-option label="周四" value="THU" />
            <el-option label="周五" value="FRI" />
            <el-option label="周六" value="SAT" />
            <el-option label="周日" value="SUN" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行时间">
          <el-time-picker
            v-model="weeklyTime"
            format="HH:mm"
            value-format="HH:mm"
            placeholder="选择时间"
            @change="generateCron"
          />
        </el-form-item>
      </template>

      <!-- 每月：选择日期和时间 -->
      <template v-if="frequencyType === 'monthly'">
        <el-form-item label="日期">
          <el-input-number v-model="monthDay" :min="1" :max="31" @change="generateCron" style="width: 80px" />
        </el-form-item>
        <el-form-item label="执行时间">
          <el-time-picker
            v-model="monthlyTime"
            format="HH:mm"
            value-format="HH:mm"
            placeholder="选择时间"
            @change="generateCron"
          />
        </el-form-item>
      </template>

      <!-- 自定义：手动输入 -->
      <el-form-item v-if="frequencyType === 'custom'" label="Cron表达式">
        <el-input v-model="customCron" @input="handleCustomInput" style="width: 200px" placeholder="0 0 10 ? * MON" />
      </el-form-item>
    </el-form>

    <div class="cron-result">
      <el-tag>{{ cronExpression }}</el-tag>
      <span style="margin-left: 10px; color: #999; font-size: 12px">{{ cronDescription }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: '0 0 10 * * ?'
  }
})

const emit = defineEmits(['update:modelValue'])

const frequencyType = ref('daily')
const dailyTime = ref('10:00')
const weekDay = ref('MON')
const weeklyTime = ref('10:00')
const monthDay = ref(1)
const monthlyTime = ref('10:00')
const customCron = ref('')

// Function definitions before computed properties
const generateCronExpression = () => {
  if (frequencyType.value === 'daily') {
    const [hour, minute] = dailyTime.value.split(':')
    return `0 ${minute} ${hour} * * ?`
  } else if (frequencyType.value === 'weekly') {
    const [hour, minute] = weeklyTime.value.split(':')
    return `0 ${minute} ${hour} ? * ${weekDay.value}`
  } else if (frequencyType.value === 'monthly') {
    const [hour, minute] = monthlyTime.value.split(':')
    return `0 ${minute} ${hour} ${monthDay.value} * ?`
  }
  return ''
}

// Function to parse cron expression back to UI state
const parseCron = (cron) => {
  if (!cron) {
    frequencyType.value = 'daily'
    dailyTime.value = '10:00'
    weekDay.value = 'MON'
    weeklyTime.value = '10:00'
    monthDay.value = 1
    monthlyTime.value = '10:00'
    customCron.value = ''
    return
  }

  const parts = cron.split(' ')
  if (parts.length < 6) {
    frequencyType.value = 'custom'
    customCron.value = cron
    return
  }

  const [sec, min, hour, dayOfMonth, month, dayOfWeek] = parts

  // Time string format HH:mm
  const timeStr = `${hour.padStart(2, '0')}:${min.padStart(2, '0')}`

  // Check Daily: 0 min hour * * ?
  if (dayOfMonth === '*' && month === '*' && dayOfWeek === '?') {
    frequencyType.value = 'daily'
    dailyTime.value = timeStr
  } 
  // Check Weekly: 0 min hour ? * weekDay
  else if (dayOfMonth === '?' && month === '*' && dayOfWeek !== '*' && dayOfWeek !== '?') {
    frequencyType.value = 'weekly'
    weekDay.value = dayOfWeek
    weeklyTime.value = timeStr
  }
  // Check Monthly: 0 min hour dayOfMonth * ?
  else if (dayOfMonth !== '*' && dayOfMonth !== '?' && month === '*' && dayOfWeek === '?') {
    frequencyType.value = 'monthly'
    monthDay.value = parseInt(dayOfMonth)
    monthlyTime.value = timeStr
  }
  // Custom
  else {
    frequencyType.value = 'custom'
    customCron.value = cron
  }
}

const cronExpression = computed(() => {
  if (frequencyType.value === 'custom') {
    return customCron.value
  }
  return generateCronExpression()
})

const cronDescription = computed(() => {
  if (frequencyType.value === 'daily') {
    return `每天 ${dailyTime.value} 执行`
  } else if (frequencyType.value === 'weekly') {
    const dayNames = { MON: '周一', TUE: '周二', WED: '周三', THU: '周四', FRI: '周五', SAT: '周六', SUN: '周日' }
    return `每${dayNames[weekDay.value] || weekDay.value} ${weeklyTime.value} 执行`
  } else if (frequencyType.value === 'monthly') {
    return `每月${monthDay.value}号 ${monthlyTime.value} 执行`
  } else {
    return '自定义 Cron 表达式'
  }
})

// Watch cron expression and emit changes
watch(cronExpression, (newVal) => {
  if (newVal !== props.modelValue) {
    emit('update:modelValue', newVal)
  }
})

// Initialize and respond to prop changes
watch(() => props.modelValue, (newVal) => {
  // Only parse if the value is different from the current generated one
  // to avoid infinite loops and unnecessary UI updates while typing in custom mode
  if (newVal !== cronExpression.value) {
    parseCron(newVal)
  }
}, { immediate: true })

const generateCron = () => {
  // The computed property will auto-update
}

const handleFrequencyChange = () => {
  // Reset values to defaults when switching types if needed, 
  // or just let generateCronExpression take care of it
}

const handleCustomInput = () => {
  // Custom input is handled via customCron ref which triggers computed cronExpression
}
</script>

<style scoped>
.cron-generator {
  padding: 10px;
  background: #f5f5f5;
  border-radius: 4px;
}

.cron-result {
  margin-top: 10px;
  padding: 10px;
  background: white;
  border-radius: 4px;
}
</style>
