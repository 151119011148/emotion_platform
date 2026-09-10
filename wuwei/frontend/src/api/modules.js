import api from './index'

export const authApi = {
  login: data => api.post('/auth/login', data),
  register: data => api.post('/auth/register', data)
}

export const sentimentApi = {
  today: () => api.get('/sentiment/today'),
  byDate: date => api.get(`/sentiment/${date}`),
  curve: () => api.get('/sentiment/curve')
}

export const tiantiApi = {
  latest: () => api.get('/tianti/latest'),
  byDate: date => api.get(`/tianti/${date}`),
  shoubanLatest: () => api.get('/tianti/latest/shouban'),
  shoubanByDate: date => api.get(`/tianti/${date}/shouban`)
}

export const conceptApi = {
  main: () => api.get('/concept/main')
}

export const dragonApi = {
  byDate: date => api.get(`/dragon/${date}`)
}

export const rotationApi = {
  byDate: date => api.get(`/rotation/${date}`)
}

export const monitorApi = {
  pool: () => api.get('/monitor/pool')
}

export const nodeApi = {
  history: () => api.get('/node/history')
}

export const calcApi = {
  run: data => api.post('/calc/run', data)
}
