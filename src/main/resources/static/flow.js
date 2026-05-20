const state = {
  snapshot: null,
};

async function fetchFlowSnapshot() {
  const response = await fetch("/api/dashboard/transaction-flow");
  if (!response.ok) {
    throw new Error("Failed to fetch transaction flow snapshot");
  }

  state.snapshot = await response.json();
  render();
}

function render() {
  const snapshot = state.snapshot;
  if (!snapshot) {
    return;
  }

  setText("flowTotalTransactions", number(snapshot.totalTransactions));
  setText("flowInFlightTransactions", number(snapshot.inFlightTransactions));
  setText("flowApprovalRate", `${snapshot.approvalRateLastFiveMinutes.toFixed(1)}%`);
  setText("flowWindowLabel", `${Math.round(snapshot.windowSeconds / 60)}m`);
  setText(
    "flowGeneratedAt",
    `Updated ${new Date(snapshot.generatedAtEpochMs).toLocaleTimeString()}`
  );

  renderFlow(snapshot.steps, snapshot.connections);
}

function renderFlow(steps, connections) {
  const root = document.getElementById("flowMap");
  root.innerHTML = "";

  steps.forEach((step, index) => {
    root.appendChild(createStep(step));

    if (index < steps.length - 1) {
      const connection = connections[index];
      if (connection) {
        root.appendChild(createConnection(connection));
      }
    }
  });
}

function createStep(step) {
  const card = document.createElement("article");
  card.className = "flow-step";

  const throughputSeries = step.throughputSeries || [];
  const states = step.states
    .map(
      (flowState) => `
        <div class="flow-state-pill" data-accent="${flowState.accent}">
          <span>${flowState.label}</span>
          <strong>${number(flowState.count)}</strong>
        </div>`
    )
    .join("");

  const latestThroughput = throughputSeries.length
    ? throughputSeries[throughputSeries.length - 1].count
    : 0;
  const settlementThroughput = throughputSeries.length
    ? throughputSeries.reduce((sum, point) => sum + point.count, 0) / throughputSeries.length
    : 0;
  const throughputLabel = step.id === "settlement" ? "Settlement throughput" : "Stage throughput";
  const throughputValue = step.id === "settlement" ? settlementThroughput : latestThroughput;
  const throughputUnit = "tx/s";
  const throughputText =
    step.id === "settlement"
      ? `${Math.round(throughputValue)} ${throughputUnit}`
      : `${number(throughputValue)} ${throughputUnit}`;
  const maxThroughput = Math.max(1, ...throughputSeries.map((point) => point.count));
  const linePath = seriesLinePath(throughputSeries, maxThroughput, 220, 92, 8);
  const areaPath = `${linePath} L 212 84 L 8 84 Z`;

  card.innerHTML = `
    <div class="flow-step__header">
      <span class="flow-step__index">${step.title}</span>
      <strong class="flow-step__total">${number(step.total)}</strong>
    </div>
    <div class="flow-step__throughput">
      <div class="flow-step__throughput-meta">
        <span>${throughputLabel}</span>
        <strong>${throughputText}</strong>
      </div>
      <svg class="flow-step__throughput-chart" viewBox="0 0 220 92" role="img" aria-label="${step.title} throughput line chart">
        <path class="flow-step__throughput-area" d="${areaPath}"></path>
        <path class="flow-step__throughput-line" d="${linePath}"></path>
      </svg>
    </div>
    <p class="flow-step__description">${step.description}</p>
    <div class="flow-step__states">${states}</div>
  `;

  return card;
}

function seriesLinePath(points, maxValue, width, height, inset) {
  if (!points.length) {
    return `M ${inset} ${height - inset} L ${width - inset} ${height - inset}`;
  }

  const usableWidth = width - inset * 2;
  const usableHeight = height - inset * 2;
  return points
    .map((point, index) => {
      const x = inset + (usableWidth * index) / Math.max(1, points.length - 1);
      const y = inset + usableHeight - (point.count / maxValue) * usableHeight;
      return `${index === 0 ? "M" : "L"} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");
}

function createConnection(connection) {
  const connector = document.createElement("div");
  connector.className = "flow-connector";

  const pulseCount = pulseQuantity(connection.count);
  const pulseDuration = pulseDurationSeconds(connection.count);
  const pulses = Array.from({ length: pulseCount }, (_, index) => {
    const delay = ((pulseDuration / pulseCount) * index).toFixed(2);
    return `<span class="flow-pulse" style="animation-delay:${delay}s;animation-duration:${pulseDuration}s"></span>`;
  }).join("");

  connector.innerHTML = `
    <div class="flow-connector__meta">
      <span>${connection.label}</span>
      <strong>${number(connection.count)}</strong>
    </div>
    <div class="flow-connector__track">${pulses}</div>
  `;

  return connector;
}

function pulseQuantity(count) {
  if (count <= 0) {
    return 0;
  }
  return Math.max(1, Math.min(9, Math.round(Math.sqrt(count / 3))));
}

function pulseDurationSeconds(count) {
  if (count <= 0) {
    return 3.8;
  }
  return Math.max(1.8, 4.2 - Math.log10(count + 1) * 1.1);
}

function setText(id, value) {
  document.getElementById(id).textContent = value;
}

function number(value) {
  return new Intl.NumberFormat("en-GB").format(value);
}

async function boot() {
  await fetchFlowSnapshot();
  setInterval(() => {
    fetchFlowSnapshot().catch((error) => {
      console.error(error);
      setText("flowGeneratedAt", "Flow refresh failed");
    });
  }, 1000);
}

boot().catch((error) => {
  console.error(error);
  setText("flowGeneratedAt", "Initial flow load failed");
});
