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

  const states = step.states
    .map(
      (flowState) => `
        <div class="flow-state-pill" data-accent="${flowState.accent}">
          <span>${flowState.label}</span>
          <strong>${number(flowState.count)}</strong>
        </div>`
    )
    .join("");

  card.innerHTML = `
    <div class="flow-step__header">
      <span class="flow-step__index">${step.title}</span>
      <strong class="flow-step__total">${number(step.total)}</strong>
    </div>
    <p class="flow-step__description">${step.description}</p>
    <div class="flow-step__states">${states}</div>
  `;

  return card;
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
