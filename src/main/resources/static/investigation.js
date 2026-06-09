const currencyFormatter = new Intl.NumberFormat("en-GB", {
  style: "currency",
  currency: "GBP",
});

async function postJson(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed: ${response.status}`);
  }
  return response.json();
}

function renderSemanticInvestigation(response) {
  const root = document.getElementById("semanticResults");
  const meta = document.getElementById("semanticMeta");
  root.innerHTML = "";
  setText("semanticStatus", response.message);
  meta.textContent = response.available
    ? `${number(response.indexedPayments)} indexed payments`
    : "MariaDB vector search unavailable";

  if (!response.available || !response.results.length) {
    root.innerHTML = `<div class="semantic-empty">${escapeHtml(response.message)}</div>`;
    return;
  }

  root.innerHTML = response.results
    .map(
      (row) => `
        <article class="semantic-result">
          <div class="semantic-result__header">
            <div>
              <strong>${escapeHtml(row.paymentId)}</strong>
              <span>${escapeHtml(row.status)} · ${escapeHtml(row.source)}</span>
            </div>
            <span class="semantic-score">${row.relevancePercent.toFixed(1)}%</span>
          </div>
          <p>${escapeHtml(row.summary)}</p>
          <div class="semantic-result__facts">
            <span>${escapeHtml(row.merchantId)}</span>
            <span>${formatMinor(row.amountMinor)}</span>
            <span>Fraud ${row.fraudScore.toFixed(1)}</span>
            <span>Distance ${row.distance.toFixed(4)}</span>
          </div>
        </article>`
    )
    .join("");
}

async function runSemanticSearch() {
  const query = document.getElementById("semanticQuery").value.trim();
  if (!query) {
    setText("semanticStatus", "Enter a query");
    return;
  }

  setText("semanticStatus", "Searching MariaDB vectors...");
  const response = await postJson("/api/investigation/semantic", { query, limit: 8 });
  renderSemanticInvestigation(response);
}

function setText(id, value) {
  document.getElementById(id).textContent = value;
}

function number(value) {
  return new Intl.NumberFormat("en-GB").format(value);
}

function formatMinor(minor) {
  return currencyFormatter.format(minor / 100);
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

document.getElementById("semanticForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await runSemanticSearch();
  } catch (error) {
    console.error(error);
    setText("semanticStatus", "Investigation search failed");
  }
});
