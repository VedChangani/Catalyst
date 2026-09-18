import apiClient from "../util/apiClient.js";

export const addUser = async (user) => {
   return await apiClient.post('/admin/register', user);
}

export const deleteUser = async (id) => {
    return await apiClient.delete(`/admin/users/${id}`);
}

export const fetchUsers = async () => {
    return await apiClient.get('/admin/users');
}
