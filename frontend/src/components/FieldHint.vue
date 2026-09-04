<template>
  <el-popover
    :trigger="isMobile ? 'click' : 'hover'"
    :width="isMobile ? 240 : 300"
    placement="top"
    popper-class="field-hint-popper"
  >
    <template #reference>
      <span class="hint-icon" role="button" :aria-label="`${label}说明`" @click.stop>
        <el-icon><QuestionFilled /></el-icon>
      </span>
    </template>
    <div class="hint-body">
      <div v-if="label" class="hint-title">{{ label }}</div>
      <div class="hint-text">{{ text }}</div>
    </div>
  </el-popover>
</template>

<script setup>
import { QuestionFilled } from '@element-plus/icons-vue'
import { useBreakpoint } from '../composables/useBreakpoint'

defineProps({
  text: { type: String, required: true },
  label: { type: String, default: '' }
})

const { isMobile } = useBreakpoint()
</script>

<style scoped>
.hint-icon {
  display: inline-flex;
  align-items: center;
  color: var(--text-placeholder);
  cursor: help;
  font-size: 13px;
  transition: color var(--ease);
}

.hint-icon:hover {
  color: var(--brand);
}

.hint-title {
  margin-bottom: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.hint-text {
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
}
</style>
