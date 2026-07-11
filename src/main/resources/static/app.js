function getCookie(name) {
  const value = `; ${document.cookie}`;
  const parts = value.split(`; ${name}=`);
  if (parts.length === 2) return parts.pop().split(';').shift();
  return null;
}

const api = (path, options = {}) => {
  const operatorSelect = document.getElementById("operator-select");
  const userId = operatorSelect ? operatorSelect.value : "anonymous";
  const authHeader = "Basic " + btoa(userId + ":password");
  const csrfToken = getCookie("XSRF-TOKEN");
  const headers = { 
    "Content-Type": "application/json", 
    "Authorization": authHeader, 
    ...options.headers 
  };
  if (csrfToken) {
    headers["X-XSRF-TOKEN"] = csrfToken;
  }
  return fetch(path, {
    headers,
    ...options,
  });
};

function money(n) {
  const x = Number(n);
  if (Number.isNaN(x)) return "—";
  return x.toLocaleString(undefined, { style: "currency", currency: "USD" });
}

function setMessage(el, text, cls) {
  if (!text) {
    el.textContent = "";
    el.classList.remove("active");
    return;
  }
  el.textContent = text;
  el.className = "message " + (cls || "") + " active";
}

// Pagination & Filter States
let currentPage = 0;
const pageSize = 10;
let totalPages = 1;
let searchQuery = "";
let categoryFilter = "all";

// Modal State Management
let modalConfirmCallback = null;

function showConfirmModal(message, onConfirm) {
  const modal = document.getElementById("confirm-modal");
  const msgEl = document.getElementById("modal-message");
  msgEl.textContent = message;
  modal.classList.add("active");
  modal.setAttribute("aria-hidden", "false");
  modalConfirmCallback = onConfirm;
}

function hideConfirmModal() {
  const modal = document.getElementById("confirm-modal");
  modal.classList.remove("active");
  modal.setAttribute("aria-hidden", "true");
  modalConfirmCallback = null;
}

document.getElementById("modal-cancel").addEventListener("click", hideConfirmModal);
document.getElementById("modal-confirm").addEventListener("click", () => {
  if (modalConfirmCallback) {
    modalConfirmCallback();
  }
  hideConfirmModal();
});

// Dynamic form validation helpers
function clearErrors() {
  document.querySelectorAll(".invalid-input").forEach((el) => el.classList.remove("invalid-input"));
  document.querySelectorAll(".field-error").forEach((el) => el.remove());
}

function showFieldError(inputName, message) {
  const input = document.querySelector(`[name="${inputName}"]`);
  if (!input) return;
  input.classList.add("invalid-input");
  const fieldWrapper = input.closest(".form-field");
  if (fieldWrapper) {
    const err = document.createElement("span");
    err.className = "field-error";
    err.textContent = message;
    fieldWrapper.appendChild(err);
  }
}

async function loadItems() {
  const params = new URLSearchParams({
    page: currentPage,
    size: pageSize,
  });
  if (searchQuery) params.append("search", searchQuery);
  if (categoryFilter && categoryFilter !== "all") params.append("category", categoryFilter);

  const res = await api(`/api/items?${params.toString()}`);
  if (!res.ok) throw new Error("Failed to load items");
  return res.json();
}

async function loadCategories() {
  try {
    const res = await api("/api/items/categories");
    if (!res.ok) throw new Error("Failed to load categories");
    const categories = await res.json();
    const select = document.getElementById("category-filter");
    const currentValue = categoryFilter;

    select.innerHTML = '<option value="all">All Categories</option>';
    for (const cat of categories) {
      const opt = document.createElement("option");
      opt.value = cat;
      opt.textContent = cat;
      select.appendChild(opt);
    }

    if (categories.includes(currentValue)) {
      select.value = currentValue;
    } else {
      categoryFilter = "all";
      select.value = "all";
    }
  } catch (e) {
    console.error("Error fetching distinct categories", e);
  }
}

async function loadReport(threshold) {
  const res = await api(`/api/reports/summary?lowStockThreshold=${encodeURIComponent(threshold)}`);
  if (!res.ok) throw new Error("Failed to load report");
  return res.json();
}

function renderItems(pageResponse) {
  const tbody = document.querySelector("#items-table tbody");
  tbody.innerHTML = "";

  const items = pageResponse.content || [];
  totalPages = pageResponse.totalPages || 1;
  currentPage = pageResponse.page || 0;

  // Render pagination numbers
  document.getElementById("page-info").textContent = `Page ${currentPage + 1} of ${Math.max(1, totalPages)}`;
  document.getElementById("prev-page").disabled = currentPage === 0;
  document.getElementById("next-page").disabled = currentPage >= totalPages - 1;

  if (items.length === 0) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 7;
    td.style.textAlign = "center";
    td.style.padding = "3rem 1.5rem";

    const divEmpty = document.createElement("div");
    divEmpty.className = "empty-state";

    const divIcon = document.createElement("div");
    divIcon.className = "empty-icon";
    divIcon.textContent = "🔍";
    divEmpty.appendChild(divIcon);

    const pTitle = document.createElement("p");
    pTitle.className = "empty-title";
    pTitle.style.margin = "0.5rem 0 0.25rem";
    pTitle.style.fontWeight = "600";
    pTitle.style.color = "var(--text)";
    pTitle.textContent = "No items found";
    divEmpty.appendChild(pTitle);

    const pSub = document.createElement("p");
    pSub.className = "empty-subtitle";
    pSub.style.margin = "0";
    pSub.style.fontSize = "0.85rem";
    pSub.style.color = "var(--muted)";
    pSub.textContent = "We couldn't find any items matching your filters.";
    divEmpty.appendChild(pSub);

    td.appendChild(divEmpty);
    tr.appendChild(td);
    tbody.appendChild(tr);
    return;
  }

  for (const it of items) {
    const tr = document.createElement("tr");

    const tdSku = document.createElement("td");
    tdSku.textContent = it.sku;
    tr.appendChild(tdSku);

    const tdName = document.createElement("td");
    tdName.textContent = it.name;
    tr.appendChild(tdName);

    const tdQty = document.createElement("td");
    tdQty.className = "qty-cell";
    tdQty.textContent = it.quantity;
    tr.appendChild(tdQty);

    const tdPrice = document.createElement("td");
    tdPrice.textContent = money(it.unitPrice);
    tr.appendChild(tdPrice);

    const tdCategory = document.createElement("td");
    tdCategory.textContent = it.category;
    tr.appendChild(tdCategory);

    const tdAdjust = document.createElement("td");
    tdAdjust.className = "adjust-cell";

    const input = document.createElement("input");
    input.type = "number";
    input.className = "delta-input";
    input.dataset.id = it.id;
    input.value = "1";
    input.min = "-99999";
    input.max = "99999";
    input.setAttribute("aria-label", `Delta for ${it.sku}`);
    tdAdjust.appendChild(input);

    const btnApply = document.createElement("button");
    btnApply.type = "button";
    btnApply.className = "secondary apply-delta";
    btnApply.dataset.id = it.id;
    btnApply.textContent = "Apply Δ";
    tdAdjust.appendChild(btnApply);

    tr.appendChild(tdAdjust);

    const tdDelete = document.createElement("td");
    const btnDelete = document.createElement("button");
    btnDelete.type = "button";
    btnDelete.className = "danger delete-item";
    btnDelete.dataset.id = it.id;
    btnDelete.textContent = "Remove";
    tdDelete.appendChild(btnDelete);
    tr.appendChild(tdDelete);

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
    btn.addEventListener("click", () => {
      const itemName = btn.closest('tr').cells[1].textContent;
      showConfirmModal(`Remove item "${itemName}" from inventory?`, () => {
        deleteItem(btn.dataset.id);
      });
    });
  });
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
  if (report.lowStockItems.length === 0) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td colspan="4" style="text-align: center; color: var(--muted); padding: 0.8rem;">All items are well-stocked.</td>`;
    lowBody.appendChild(tr);
    return;
  }

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

async function refreshAll() {
  const msg = document.getElementById("form-message");
  try {
    const [pageResponse, _] = await Promise.all([
      loadItems(),
      loadCategories()
    ]);
    renderItems(pageResponse);
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
  clearErrors();
  const form = e.target;
  const msg = document.getElementById("form-message");
  
  let hasError = false;
  const sku = form.sku.value.trim();
  const name = form.name.value.trim();
  const quantityVal = form.quantity.value;
  const unitPriceVal = form.unitPrice.value;
  const category = form.category.value.trim() || "General";

  if (!sku) {
    showFieldError("sku", "SKU is required");
    hasError = true;
  }
  if (!name) {
    showFieldError("name", "Item name is required");
    hasError = true;
  }
  if (quantityVal === "" || Number(quantityVal) < 0) {
    showFieldError("quantity", "Quantity must be ≥ 0");
    hasError = true;
  }
  if (unitPriceVal === "" || Number(unitPriceVal) < 0) {
    showFieldError("unitPrice", "Unit price must be ≥ 0");
    hasError = true;
  }

  if (hasError) {
    setMessage(msg, "Please fix verification errors.", "error");
    return;
  }

  const body = {
    sku,
    name,
    quantity: Number(quantityVal),
    unitPrice: unitPriceVal,
    category,
  };

  const res = await api("/api/items", { method: "POST", body: JSON.stringify(body) });
  if (res.status === 201) {
    setMessage(msg, "Item added.", "ok");
    form.reset();
    form.category.value = "General";
    form.quantity.value = "0";
    form.unitPrice.value = "0";
    currentPage = 0;
    await refreshAll();
  } else if (res.status === 409) {
    setMessage(msg, "That SKU already exists.", "error");
    showFieldError("sku", "SKU already in use");
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
    const updatedItem = await res.json();
    setMessage(msg, "Stock updated.", "ok");
    
    // Perform in-place DOM update of the modified item to avoid full table redraw
    const row = document.querySelector(`.delta-input[data-id="${id}"]`).closest("tr");
    if (row) {
      row.querySelector(".qty-cell").textContent = updatedItem.quantity;
    }
    
    // Update the dashboard reports asynchronously without items table reload
    const th = document.getElementById("threshold").value || "5";
    const report = await loadReport(th);
    renderReport(report);
  } else if (res.status === 409) {
    setMessage(msg, "Adjust failed: Quantity cannot drop below 0.", "error");
    await refreshAll();
  } else if (res.status === 404) {
    setMessage(msg, "Item not found or archived.", "error");
    await refreshAll();
  } else {
    setMessage(msg, "Update failed.", "error");
  }
}

async function deleteItem(id) {
  const msg = document.getElementById("form-message");
  const res = await api(`/api/items/${id}`, { method: "DELETE" });
  if (res.status === 204) {
    setMessage(msg, "Item removed (soft-deleted).", "ok");
    const itemsTbody = document.querySelector("#items-table tbody");
    if (itemsTbody.children.length === 1 && currentPage > 0) {
      currentPage--;
    }
    await refreshAll();
  } else {
    setMessage(msg, "Could not remove item.", "error");
  }
}

// Add event listeners for new controls
let searchTimeout = null;
document.getElementById("search-input").addEventListener("input", (e) => {
  clearTimeout(searchTimeout);
  searchQuery = e.target.value.trim();
  searchTimeout = setTimeout(() => {
    currentPage = 0;
    refreshAll();
  }, 250);
});

document.getElementById("category-filter").addEventListener("change", (e) => {
  categoryFilter = e.target.value;
  currentPage = 0;
  refreshAll();
});

document.getElementById("prev-page").addEventListener("click", () => {
  if (currentPage > 0) {
    currentPage--;
    refreshAll();
  }
});

document.getElementById("next-page").addEventListener("click", () => {
  if (currentPage < totalPages - 1) {
    currentPage++;
    refreshAll();
  }
});

document.getElementById("refresh-report").addEventListener("click", refreshAll);

// Stream CSV directly from the backend
document.getElementById("export-csv").addEventListener("click", () => {
  window.location.href = "/api/items/export";
});

refreshAll();
