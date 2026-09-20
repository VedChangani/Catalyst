import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import Item from "../Item/Item.jsx";
import SearchBox from "../SearchBox/SearchBox.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const DisplayItems = ({selectedCategory}) => {
    const {itemsData} = useContext(AppContext);
    const [searchText, setSearchText] = useState("");

    const filteredItems = itemsData.filter(item => {
        if(!selectedCategory) return true;
        return item.categoryId === selectedCategory;
    }).filter(item => item.name.toLowerCase().includes(searchText.toLowerCase()));

    return (
        <div>
            <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                    <h2 className="text-xl font-extrabold">Products</h2>
                    <p className="text-sm text-muted">{filteredItems.length} items in view</p>
                </div>
                <SearchBox onSearch={setSearchText} />
            </div>
            {filteredItems.length === 0 ? (
                <EmptyState
                    title="No products to show"
                    description="Try another category or search term."
                />
            ) : (
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
                    {filteredItems.map((item, index) => (
                        <Item
                            key={item.itemId || index}
                            itemName={item.name}
                            itemPrice={item.price}
                            itemImage={item.imgUrl}
                            itemId={item.itemId}
                            categoryName={item.categoryName}
                            active={item.active}
                            availableQuantity={item.availableQuantity}
                            lowStockThreshold={item.lowStockThreshold}
                        />
                    ))}
                </div>
            )}
        </div>
    )
}

export default DisplayItems;
