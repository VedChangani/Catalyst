import ItemForm from "../../components/ItemForm/ItemForm.jsx";
import ItemList from "../../components/ItemList/ItemList.jsx";
import PageHeader from "../../ui/PageHeader.jsx";

const ManageItems = () => {
    return (
        <div className="mx-auto w-full max-w-[1440px] px-4 py-6 sm:px-6 lg:px-8">
            <PageHeader
                kicker="Catalog"
                title="Manage Items"
                description="Add products, set prices, and keep the floor stock list current."
            />
            <div className="grid gap-5 lg:grid-cols-[minmax(280px,0.42fr)_minmax(0,1fr)]">
                <div className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 className="mb-4 text-lg font-extrabold">Add item</h2>
                    <ItemForm />
                </div>
                <div className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 className="mb-4 text-lg font-extrabold">Product list</h2>
                    <ItemList />
                </div>
            </div>
        </div>
    )
}

export default ManageItems;
