<template>
  <section class="not-found">
    <div class="not-found-card">
      <div class="eyebrow">404</div>
      <h1>页面不存在</h1>
      <p>当前路径没有对应页面，请返回上一层或进入项目列表继续操作。</p>
    </div>
  </section>
</template>

<style scoped>
.not-found {
  min-height: calc(100vh - 160px);
  display: grid;
  place-items: center;
}

.not-found-card {
  width: min(520px, 100%);
  padding: 30px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

</style>
