import request from '../../utils/request'

export function listJobs() {
    return request.get('/job/list')
}

export function getJob(id) {
    return request.get(`/job/${id}`)
}

export function addJob(data) {
    return request.post('/job/add', data)
}

export function updateJob(data) {
    return request.post('/job/update', data)
}

export function deleteJob(id) {
    return request.post(`/job/delete/${id}`)
}

export function runJob(id) {
    return request.post(`/job/run/${id}`)
}

export function changeStatus(id, status) {
    return request.post('/job/changeStatus', null, {
        params: { id, status }
    })
}
