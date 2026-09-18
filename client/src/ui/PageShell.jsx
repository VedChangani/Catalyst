const PageShell = ({ children, wide = false }) => {
  return (
    <div className={`mx-auto w-full px-4 py-6 sm:px-6 lg:px-8 ${wide ? "max-w-[1440px]" : "max-w-6xl"}`}>
      {children}
    </div>
  );
};

export default PageShell;
