const Select = ({ label, id, className = "", children, ...props }) => {
  return (
    <div className="flex w-full flex-col gap-1.5">
      {label && (
        <label htmlFor={id} className="text-sm font-bold text-ink">
          {label}
        </label>
      )}
      <select id={id} className={`nb-input ${className}`} {...props}>
        {children}
      </select>
    </div>
  );
};

export default Select;
