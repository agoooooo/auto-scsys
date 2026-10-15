const currentYear = document.querySelector("#copyright-year");
if (currentYear) currentYear.textContent = String(new Date().getFullYear());

const consoleUrl = window.flowpilotSiteConfig?.consoleUrl;
if (consoleUrl) {
  try {
    const parsedUrl = new URL(consoleUrl);
    if (parsedUrl.protocol === "https:" || parsedUrl.protocol === "http:") {
      document.querySelectorAll("[data-console-link]").forEach((link) => {
        link.href = parsedUrl.href;
        link.removeAttribute("aria-disabled");
        link.textContent = "登录控制台";
        const arrow = document.createElement("span");
        arrow.setAttribute("aria-hidden", "true");
        arrow.textContent = " →";
        link.append(arrow);
      });
    }
  } catch {
    // An invalid deployment URL leaves the CTA disabled instead of linking visitors elsewhere.
  }
}

document.querySelectorAll(".mobile-nav nav a").forEach((link) => {
  link.addEventListener("click", () => {
    link.closest("details")?.removeAttribute("open");
  });
});
