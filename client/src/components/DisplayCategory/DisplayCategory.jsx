import Category from "../Category/Category.jsx";
import {assets} from "../../assets/assets.js";

const DisplayCategory = ({selectedCategory, setSelectedCategory, categories}) => {
    return (
        <div className="flex min-w-max gap-3">
            <Category
                categoryName="All Items"
                imgUrl={assets.device}
                numberOfItems={categories.reduce((acc, cat) => acc + cat.items, 0)}
                bgColor="#2563EB"
                isSelected={selectedCategory === ""}
                onClick={() => setSelectedCategory("")}
            />
            {categories.map(category => (
                <Category
                    key={category.categoryId}
                    categoryName={category.name}
                    imgUrl={category.imgUrl}
                    numberOfItems={category.items}
                    bgColor={category.bgColor}
                    isSelected={selectedCategory === category.categoryId}
                    onClick={() => setSelectedCategory(category.categoryId)}
                />
            ))}
        </div>
    )
}

export default DisplayCategory;
