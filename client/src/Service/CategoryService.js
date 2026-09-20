import apiClient from "../util/apiClient.js";

export const addCategory = async (category) => {
    return await apiClient.post('/admin/categories', category);
}

export const deleteCategory = async (categoryId) => {
    return await apiClient.delete(`/admin/categories/${categoryId}`);
}

export const fetchCategories = async () => {
    return await apiClient.get('/categories');
}
