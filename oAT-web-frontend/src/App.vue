<template>
  <RouterView />
  <AppDialogHost />
</template>

<script setup lang="ts">
import { RouterView } from 'vue-router'

import AppDialogHost from '@/components/AppDialogHost.vue'
</script>
