const state = {
  snapshot: null,
};

const currencyFormatter = new Intl.NumberFormat("en-GB", {
  style: "currency",
  currency: "GBP",
});

async function fetchSnapshot() {
  const response = await fetch("/api/dashboard");
  if (!response.ok) {
    throw new Error("Failed to fetch dashboard snapshot");
  }

  state.snapshot = await response.json();
  render();
}

function render() {
  const snapshot = state.snapshot;
  if (!snapshot) {
    return;
  }

  setText("throughputLastMinute", number(snapshot.throughputLastMinute));
  setText("approvalRate", `${snapshot.approvalRateLastFiveMinutes.toFixed(1)}%`);
  setText("generatedPayments", number(snapshot.simulator.generatedPayments));
  setText("activeThreshold", snapshot.fraudThreshold.toFixed(0));
  setText(
    "simulatorStatus",
    snapshot.simulator.running
      ? `Running at ${snapshot.simulator.ratePerSecond} payments/sec`
      : "Stopped"
  );

  document.getElementById("fraudThreshold").value = snapshot.fraudThreshold.toFixed(0);
  setText("fraudThresholdValue", snapshot.fraudThreshold.toFixed(0));

  renderBars(snapshot.throughputSeries);
  renderStatusMix(snapshot.statusCountsLastFiveMinutes);
  renderDeclines(snapshot.declinesByReason);
  renderTopMerchants(snapshot.topMerchants);
  renderSuspicious(snapshot.suspiciousPayments);
}

function renderBars(points) {
  const root = document.getElementById("throughputChart");
  root.innerHTML = "";
  const max = Math.max(1, ...points.map((point) => point.count));

  for (const point of points) {
    const bar = document.createElement("div");
    bar.className = "bar";
    bar.style.height = `${Math.max(4, (point.count / max) * 100)}%`;
    bar.title = `${point.count} tx/sec`;
    root.appendChild(bar);
  }
}

function renderStatusMix(statusCounts) {
  const root = document.getElementById("statusMix");
  root.innerHTML = "";

  const orderedStatuses = ["PENDING_MERCHANT", "AUTHORIZED", "CAPTURED", "REFUNDED", "DECLINED", "TIMED_OUT"];
  const remainingStatuses = Object.keys(statusCounts).filter((status) => !orderedStatuses.includes(status)).sort();
  const statuses = [...orderedStatuses, ...remainingStatuses];
  for (const status of statuses) {
    const value = statusCounts[status] || 0;
    const pill = document.createElement("div");
    pill.className = "status-pill";
    pill.innerHTML = `<span>${status}</span><strong>${number(value)}</strong>`;
    root.appendChild(pill);
  }
}

function renderDeclines(rows) {
  const root = document.getElementById("declinesTable");
  root.innerHTML = rows.length
    ? rows.map((row) => `<tr><td>${row.reason}</td><td>${number(row.count)}</td></tr>`).join("")
    : `<tr><td>No declines in window</td><td>0</td></tr>`;
}

function renderTopMerchants(rows) {
  const root = document.getElementById("topMerchantsTable");
  root.innerHTML = rows.length
    ? rows
        .map(
          (row) => `
            <tr>
              <td>${row.merchantName}<div class="muted">${row.merchantId}</div></td>
              <td>${number(row.transactionCount)}</td>
              <td>${formatMinor(row.amountMinor)}</td>
            </tr>`
        )
        .join("")
    : `<tr><td>No traffic yet</td><td>0</td><td>${formatMinor(0)}</td></tr>`;
}

function renderSuspicious(rows) {
  const root = document.getElementById("suspiciousTable");
  root.innerHTML = rows.length
    ? rows
        .map(
          (row) => `
            <tr>
              <td>${row.paymentId}</td>
              <td>${row.merchantId}</td>
              <td>${formatMinor(row.amountMinor)}</td>
              <td>${row.fraudScore.toFixed(1)}</td>
              <td>${row.status}</td>
              <td>${new Date(row.createdAtEpochMs).toLocaleTimeString()}</td>
            </tr>`
        )
        .join("")
    : `<tr><td colspan="6">No suspicious payments in window</td></tr>`;
}

async function post(url) {
  const response = await fetch(url, { method: "POST" });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed: ${response.status}`);
  }
  return response.json();
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

function wireControls() {
  document.getElementById("startSimulator").addEventListener("click", async () => {
    const rate = document.getElementById("ratePerSecond").value || 120;
    await post(`/api/simulator/start?ratePerSecond=${encodeURIComponent(rate)}`);
    await fetchSnapshot();
  });

  document.getElementById("stopSimulator").addEventListener("click", async () => {
    await post("/api/simulator/stop");
    await fetchSnapshot();
  });

  const fraudThreshold = document.getElementById("fraudThreshold");
  fraudThreshold.addEventListener("input", () => {
    setText("fraudThresholdValue", fraudThreshold.value);
  });

  document.getElementById("applyFraudThreshold").addEventListener("click", async () => {
    await post(`/api/admin/fraud-threshold?value=${encodeURIComponent(fraudThreshold.value)}`);
    await fetchSnapshot();
  });

  document.getElementById("deactivateMerchant").addEventListener("click", async () => {
    const merchantId = document.getElementById("merchantId").value;
    const merchant = await post(`/api/admin/merchants/${encodeURIComponent(merchantId)}/status?active=false`);
    setText("merchantStatus", `${merchant.merchantId} is now inactive`);
    await fetchSnapshot();
  });

  document.getElementById("activateMerchant").addEventListener("click", async () => {
    const merchantId = document.getElementById("merchantId").value;
    const merchant = await post(`/api/admin/merchants/${encodeURIComponent(merchantId)}/status?active=true`);
    setText("merchantStatus", `${merchant.merchantId} is active again`);
    await fetchSnapshot();
  });
}

async function boot() {
  wireControls();
  await fetchSnapshot();
  setInterval(() => {
    fetchSnapshot().catch((error) => {
      console.error(error);
      setText("simulatorStatus", "Dashboard refresh failed");
    });
  }, 1000);
}

boot().catch((error) => {
  console.error(error);
  setText("simulatorStatus", "Initial dashboard load failed");
});
