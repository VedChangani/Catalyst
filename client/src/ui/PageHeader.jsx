const PageHeader = ({ kicker, title, description, actions }) => {
  return (
    <div className="mb-3 flex flex-col gap-4 border-b-2 border-ink/10 pb-6 sm:flex-row sm:items-end sm:justify-between">
      <div>
        {kicker && (
          <p className="mb-1 text-xs font-extrabold uppercase tracking-[0.18em] text-muted">
            {kicker}
          </p>
        )}
        <h1 className="text-3xl font-extrabold tracking-tight text-ink sm:text-4xl">
          {title}
        </h1>
        {description && (
          <p className="mt-2 max-w-2xl text-base text-muted">{description}</p>
        )}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
};

export default PageHeader;
