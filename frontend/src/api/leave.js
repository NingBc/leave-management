import request from '../utils/request'

export function getAllAccounts(year, current = 1, size = 10, expiringOnly = false) {
    return request.get('/leave/list', {
        params: { year, current, size, expiringOnly }
    })
}

/** 上年结转作废汇总 (全员口径): 作废日、剩几天、多少人共多少天 */
export function getCarryOverExpiry(year) {
    return request.get('/leave/carry-over-expiry', { params: { year } })
}

export function updateAccount(data) {
    return request.post('/leave/updateAccount', data)
}

export function addRecord(data) {
    return request.post('/leave/add-record', data)
}

export function updateRecord(data) {
    return request.post('/leave/update-record', data)
}
