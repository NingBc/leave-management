import request from '../utils/request'

export function getAllAccounts(year, current = 1, size = 10) {
    return request.get('/leave/list', {
        params: { year, current, size }
    })
}

export function updateAccount(data) {
    return request.post('/leave/updateAccount', data)
}

export function addRecord(data) {
    return request.post('/leave/add-record', data)
}
