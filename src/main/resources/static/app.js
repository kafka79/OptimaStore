const api = (path, options = {}) =>
  fetch(path, {
    headers: { "Content-Type": "application/json", ...options.headers },
    ...options,
  });

function money(n) {
  const x = Number(n);
  if (Number.isNaN(x)) return "—";
  return x.toLocaleString(undefined, { style: "currency", currency: "USD" });
}

function setMessage(el, text, cls) {
  el.textContent = text || "";
  el.className = "message" + (cls ? " " + cls : "");
}

async function loadItems() {
  const res = await api("/api/items");
  if (!res.ok) throw new Error("Failed to load items");
  return res.json();
}

async function loadReport(threshold) {
  const res = await api(`/api/reports/summary?lowStockThreshold=${encodeURIComponent(threshold)}`);
  if (!res.ok) throw new Error("Failed to load report");
  return res.json();
}

function renderItems(items) {
  const tbody = document.querySelector("#items-table tbody");
  tbody.innerHTML = "";
  for (const it of items) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${escapeHtml(it.sku)}</td>
      <td>${escapeHtml(it.name)}</td>
      <td>${it.quantity}</td>
      <td>${money(it.unitPrice)}</td>
      <td>${escapeHtml(it.category)}</td>
      <td class="adjust-cell">
        <input type="number" class="delta-input" data-id="${it.id}" value="1" min="-99999" max="99999" aria-label="Delta for ${escapeHtml(it.sku)}" />
        <button type="button" class="secondary apply-delta" data-id="${it.id}">Apply Δ</button>
      </td>
      <td><button type="button" class="danger delete-item" data-id="${it.id}">Remove</button></td>
    `;
    tbody.appendChild(tr);
  }

  tbody.querySelectorAll(".apply-delta").forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.dataset.id;
      const input = tbody.querySelector(`.delta-input[data-id="${id}"]`);
      const delta = Number(input.value);
      if (!Number.isFinite(delta)) return;
      adjustQuantity(id, delta);
    });
  });

  tbody.querySelectorAll(".delete-item").forEach((btn) => {
    btn.addEventListener("click", () => deleteItem(btn.dataset.id));
  });
}

function escapeHtml(s) {
  const d = document.createElement("div");
  d.textContent = s;
  return d.innerHTML;
}

function renderReport(report) {
  const stats = document.getElementById("report-stats");
  stats.innerHTML = `
    <div><dt>Distinct SKUs</dt><dd>${report.distinctItems}</dd></div>
    <div><dt>Total units</dt><dd>${report.totalUnitsOnHand}</dd></div>
    <div><dt>Inventory value</dt><dd>${money(report.totalInventoryValue)}</dd></div>
    <div><dt>Below threshold</dt><dd>${report.lowStockItemCount}</dd></div>
  `;

  const lowBody = document.querySelector("#low-stock-table tbody");
  lowBody.innerHTML = "";
  for (const it of report.lowStockItems) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${escapeHtml(it.sku)}</td>
      <td>${escapeHtml(it.name)}</td>
      <td>${it.quantity}</td>
      <td>${money(it.unitPrice)}</td>
    `;
    lowBody.appendChild(tr);
  }
}

function reportToCsv(items) {
  const headers = ["id", "sku", "name", "quantity", "unitPrice", "category", "updatedAt"];
  const lines = [headers.join(",")];
  for (const it of items) {
    lines.push(
      [
        it.id,
        csvEscape(it.sku),
        csvEscape(it.name),
        it.quantity,
        it.unitPrice,
        csvEscape(it.category),
        csvEscape(it.updatedAt),
      ].join(",")
    );
  }
  return lines.join("\r\n");
}

function csvEscape(v) {
  const s = String(v ?? "");
  if (/[",\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

async function refreshAll() {
  const msg = document.getElementById("form-message");
  try {
    const items = await loadItems();
    renderItems(items);
    const th = document.getElementById("threshold").value || "5";
    const report = await loadReport(th);
    renderReport(report);
    setMessage(msg, "");
  } catch (e) {
    setMessage(msg, e.message || "Something went wrong", "error");
  }
}

document.getElementById("add-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.target;
  const msg = document.getElementById("form-message");
  const body = {
    sku: form.sku.value.trim(),
    name: form.name.value.trim(),
    quantity: Number(form.quantity.value),
    unitPrice: form.unitPrice.value,
    category: form.category.value.trim() || "General",
  };
  const res = await api("/api/items", { method: "POST", body: JSON.stringify(body) });
  if (res.status === 201) {
    setMessage(msg, "Item added.", "ok");
    form.reset();
    form.category.value = "General";
    form.quantity.value = "0";
    form.unitPrice.value = "0";
    await refreshAll();
  } else if (res.status === 409) {
    setMessage(msg, "That SKU already exists.", "error");
  } else if (res.status === 400) {
    setMessage(msg, "Check your inputs (numbers must be ≥ 0).", "error");
  } else {
    setMessage(msg, "Could not add item.", "error");
  }
});

async function adjustQuantity(id, delta) {
  const msg = document.getElementById("form-message");
  const res = await api(`/api/items/${id}/quantity`, {
    method: "PATCH",
    body: JSON.stringify({ delta }),
  });
  if (res.ok) {
    setMessage(msg, "Stock updated.", "ok");
    await refreshAll();
  } else if (res.status === 404) {
    setMessage(msg, "Item not found or quantity would go negative.", "error");
    await refreshAll();
  } else {
    setMessage(msg, "Update failed.", "error");
  }
}

async function deleteItem(id) {
  if (!confirm("Remove this item from inventory?")) return;
  const msg = document.getElementById("form-message");
  const res = await api(`/api/items/${id}`, { method: "DELETE" });
  if (res.status === 204) {
    setMessage(msg, "Item removed.", "ok");
    await refreshAll();
  } else {
    setMessage(msg, "Could not remove item.", "error");
  }
}

document.getElementById("refresh-report").addEventListener("click", refreshAll);

document.getElementById("export-csv").addEventListener("click", async () => {
  try {
    const items = await loadItems();
    const blob = new Blob([reportToCsv(items)], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "inventory-export.csv";
    a.click();
    URL.revokeObjectURL(a.href);
  } catch {
    document.getElementById("form-message").textContent = "Export failed.";
  }
});

refreshAll();
