<template>
  <div class="user-profile-controls">
    <div class="user-profile-controls-image-container">
      <img :src="permanentImages.instructionsRobot">
    </div>
    <div class="user-profile-controls-text-container">
      <div class="user-profile-user">
        <div class="user-profile-user-name text">{{currentUser.name}}</div>
      </div>
      <mathbot-header :font-size="headerFont" :line-height="headerFont + 2"></mathbot-header>
      <div class="user-profile-controls-text-footer">
        <div class="text nav-links about" @click="gotoMarketing">About</div>
        <div class="text nav-links sign-out" v-if="auth !== null" @click="auth.logout()">Sign Out</div>
      </div>
    </div>
  </div>
</template>

<script>
import MathbotHeader from './Mathbot_header'
export default {
  name: 'user_profile_controls',
  computed: {
    currentUser () {
      return this.$store.getters.getCurrentUser
    },
    auth () {
      return this.$store.getters.getAuth
    },
    windowWidth () {
      return window.innerWidth
    },
    headerFont () {
      if (this.windowWidth < 1024) {
        return 30
      }
    }
  },
  methods: {
    gotoMarketing () {
      this.$router.push({path: '/about'})
    }
  },
  components: {
    MathbotHeader
  },
  props: ['permanentImages']
}
</script>

<style scoped src="../css/scoped/userProfileControls.scss" lang="scss"></style>
