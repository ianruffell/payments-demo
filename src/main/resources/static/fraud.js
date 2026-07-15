const REFRESH_MS = 4000;

function fmtAmount(minor, currency) {
    const value = (minor / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return `${currency || ''} ${value}`.trim();
}

function fmtTime(epochMs) {
    if (!epochMs) return '';
    return new Date(epochMs).toLocaleTimeString();
}

async function refresh() {
    try {
        const [summaryRes, blockedRes] = await Promise.all([
            fetch('/api/fraud/summary'),
            fetch('/api/fraud/blocked')
        ]);
        const summary = await summaryRes.json();
        const blocked = await blockedRes.json();

        document.getElementById('statScreened').textContent = summary.screened.toLocaleString();
        document.getElementById('statBlocked').textContent = summary.blocked.toLocaleString();
        document.getElementById('statRate').textContent = `${summary.blockRatePercent}%`;
        document.getElementById('statSuspicious').textContent = summary.suspicious.toLocaleString();
        document.getElementById('statThreshold').textContent = summary.threshold;

        const body = document.getElementById('blockedBody');
        if (!blocked.length) {
            body.innerHTML = '<tr><td colspan="7" class="muted">No payments blocked yet. Lower the AI fraud threshold or run the simulator to generate activity.</td></tr>';
            return;
        }

        body.innerHTML = blocked.map(function (b) {
            const signals = (b.signals || []).join(', ');
            return `<tr>
                <td>${fmtTime(b.blockedAtEpochMs)}</td>
                <td>${b.paymentId}</td>
                <td>${b.accountId}</td>
                <td>${b.merchantId}</td>
                <td>${fmtAmount(b.amountMinor, b.currency)}</td>
                <td><strong>${b.fraudScore.toFixed(1)}</strong></td>
                <td class="muted">${signals}</td>
            </tr>`;
        }).join('');
    } catch (e) {
        document.getElementById('fraudMeta').textContent = 'Failed to load fraud activity';
    }
}

refresh();
setInterval(refresh, REFRESH_MS);
