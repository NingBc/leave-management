import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUserStore = defineStore('user', () => {
    const token = ref(localStorage.getItem('token') || '')
    const userId = ref(localStorage.getItem('userId') || '')
    const username = ref(localStorage.getItem('username') || '')

    const userMenus = ref([])

    const setLoginState = (newToken, newUserId, newUsername) => {
        token.value = newToken
        userId.value = newUserId
        username.value = newUsername
        localStorage.setItem('token', newToken)
        localStorage.setItem('userId', newUserId)
        localStorage.setItem('username', newUsername)
    }

    const setUserMenus = (menus) => {
        userMenus.value = menus
    }

    const logout = () => {
        token.value = ''
        userId.value = ''
        username.value = ''
        userMenus.value = []
        localStorage.removeItem('token')
        localStorage.removeItem('userId')
        localStorage.removeItem('username')
    }

    return {
        token,
        userId,
        username,
        userMenus,
        setLoginState,
        setUserMenus,
        logout
    }
})
