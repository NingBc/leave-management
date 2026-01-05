import { createRouter, createWebHashHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Layout from '../views/Layout.vue'

const routes = [
    {
        path: '/login',
        name: 'Login',
        component: Login
    },
    {
        path: '/',
        component: Layout,
        redirect: '/dashboard',
        children: [
            {
                path: 'dashboard',
                name: 'Dashboard',
                component: () => import('../views/Home.vue')
            },
            {
                path: 'system/user',
                name: 'UserManagement',
                component: () => import('../views/system/User.vue')
            },
            {
                path: 'system/role',
                name: 'RoleManagement',
                component: () => import('../views/system/Role.vue')
            },
            {
                path: 'system/menu',
                name: 'MenuManagement',
                component: () => import('../views/system/Menu.vue')
            },
            {
                path: 'leave/my',
                name: 'MyLeave',
                component: () => import('../views/leave/MyLeave.vue')
            },
            {
                path: 'leave/manage',
                name: 'LeaveManagement',
                component: () => import('../views/leave/ManageLeave.vue')
            },
            {
                path: 'monitor/job',
                name: 'JobManagement',
                component: () => import('../views/monitor/job/index.vue')
            }
        ]
    }
]

const router = createRouter({
    history: createWebHashHistory(),
    routes
})

router.beforeEach((to, from, next) => {
    const token = localStorage.getItem('token')
    if (to.path !== '/login' && !token) {
        next('/login')
    } else {
        next()
    }
})

export default router
