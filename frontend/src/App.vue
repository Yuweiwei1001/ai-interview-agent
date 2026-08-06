<script setup lang="ts">
import { NConfigProvider, NMessageProvider, NDialogProvider, type GlobalThemeOverrides } from 'naive-ui';

/* 美化：naive-ui 全局主题覆盖，主色与原 Tailwind blue-600 对齐，圆角统一为 8/16px 体系 */
const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2563eb',
    primaryColorHover: '#3b82f6',
    primaryColorPressed: '#1d4ed8',
    primaryColorSuppl: '#2563eb',
    borderRadius: '8px',
    fontFamily: '"PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Arial, system-ui, sans-serif'
  },
  /* 美化：卡片/弹窗统一 16px 圆角与柔和阴影 */
  Card: {
    borderRadius: '16px',
    boxShadow: '0 4px 24px rgba(15, 23, 42, 0.06)'
  },
  Dialog: {
    borderRadius: '16px'
  }
};
</script>

<template>
  <n-config-provider :theme-overrides="themeOverrides">
    <n-message-provider>
      <n-dialog-provider>
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
