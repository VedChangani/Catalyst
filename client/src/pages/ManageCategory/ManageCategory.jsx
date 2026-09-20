import {useContext} from "react";
import CategoryForm from "../../components/CategoryForm/CategoryForm.jsx";
import CategoryList from "../../components/CategoryList/CategoryList.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import {AppContext} from "../../context/AppContext.jsx";

const ManageCategory = () => {
    const {isCatalogLoading} = useContext(AppContext);

    return (
        <div className="mx-auto w-full max-w-[1440px] px-4 py-6 sm:px-6 lg:px-8">
            <PageHeader
                kicker="Catalog"
                title="Manage Categories"
                description="Group products so cashiers can filter the floor quickly."
            />
            <div className="grid gap-5 lg:grid-cols-[minmax(280px,0.42fr)_minmax(0,1fr)]">
                <div className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 className="mb-4 text-lg font-extrabold">Add category</h2>
                    <CategoryForm />
                </div>
                <div className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 className="mb-4 text-lg font-extrabold">Category list</h2>
                    {isCatalogLoading ? <LoadingState label="Loading categories..." /> : <CategoryList />}
                </div>
            </div>
        </div>
    )
}

export default ManageCategory;
