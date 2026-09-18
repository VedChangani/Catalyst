const Category = ({categoryName, imgUrl, numberOfItems, bgColor, isSelected, onClick}) => {
    return (
        <button
            type="button"
            onClick={onClick}
            className={`flex min-w-[200px] items-center gap-3 border-2 border-ink bg-surface px-3 py-3 text-left shadow-[2px_2px_0_#111827] transition-all duration-150 hover:translate-x-[1px] hover:translate-y-[1px] hover:shadow-[1px_1px_0_#111827] ${isSelected ? "bg-primary/10 border-ink shadow-[2px_2px_0_#111827]" : ""}`}
        >
            <span className="h-12 w-1.5 shrink-0 border-2 border-ink" style={{backgroundColor: bgColor}} />
            <img src={imgUrl} alt={categoryName} className="h-12 w-12 border-2 border-ink object-cover" />
            <span>
                <span className="block text-sm font-extrabold leading-tight">{categoryName}</span>
                <span className="block text-xs font-bold text-muted">{numberOfItems} Items</span>
            </span>
        </button>
    )
}

export default Category;
