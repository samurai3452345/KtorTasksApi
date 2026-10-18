"use strict";

(() => {
  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];
  const PAGE_SIZE = 8;
  const SESSION_KEY = "focus.session";
  const state = { token: "", user: null, mode: "login", filter: "all", search: "", offset: 0, rows: [], hasNext: false, stats: null, listVersion: 0, controller: null, detail: null, deleting: null };
  let searchTimer;
  let toastTimer;
  let authBusy = false;
  const dateFormat = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short" });
  const fullDateFormat = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit" });

  function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.classList.add("icon");
    svg.setAttribute("aria-hidden", "true");
    const use = document.createElementNS(svg.namespaceURI, "use");
    use.setAttribute("href", `#i-${name}`);
    svg.append(use);
    return svg;
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function setToken(value) {
    state.token = value;
    // Храним сессию только в этой вкладке. Пароль не сохраняется.
    try { value ? sessionStorage.setItem(SESSION_KEY, value) : sessionStorage.removeItem(SESSION_KEY); } catch { /* В приватном режиме сессия останется в памяти. */ }
  }

  function showError(selector, message = "") {
    const node = $(selector);
    node.textContent = message;
    node.hidden = !message;
  }

  function toast(message) {
    clearTimeout(toastTimer);
    $("#toast span").textContent = message;
    $("#toast").hidden = false;
    toastTimer = setTimeout(() => { $("#toast").hidden = true; }, 3500);
  }

  function closeDialogs() { $$("dialog[open]").forEach((dialog) => dialog.close()); }

  function showAuth(message = "") {
    state.listVersion++;
    state.controller?.abort();
    clearTimeout(searchTimer);
    clearTimeout(toastTimer);
    closeDialogs();
    setToken("");
    state.user = null;
    state.rows = [];
    state.stats = null;
    state.detail = null;
    state.deleting = null;
    $("#task-list").replaceChildren();
    $("#toast").hidden = true;
    $("#app-screen").hidden = true;
    $("#boot-screen").hidden = true;
    $("#auth-screen").hidden = false;
    $("#password").value = "";
    $("#password").type = "password";
    $("#toggle-password").setAttribute("aria-label", "Показать пароль");
    $("#toggle-password").setAttribute("aria-pressed", "false");
    setAuthMode("login");
    showError("#auth-error", message);
  }

  async function api(path, { method = "GET", body, signal, authenticated = true } = {}) {
    const token = authenticated ? state.token : "";
    const headers = { Accept: "application/json" };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    let response;
    try {
      response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal, cache: "no-store" });
    } catch (error) {
      if (error.name === "AbortError") throw error;
      throw new Error("Не удалось связаться с сервером. Проверьте подключение и повторите попытку.");
    }
    if (authenticated && token !== state.token) throw Object.assign(new Error("Сессия изменилась"), { silent: true });
    const data = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
      if (authenticated && response.status === 401) {
        showAuth("Сессия завершилась. Войдите ещё раз.");
        throw Object.assign(new Error("Сессия завершилась"), { silent: true });
      }
      throw new Error(data?.message || `Сервер вернул ошибку ${response.status}. Попробуйте ещё раз.`);
    }
    return data;
  }

  function setAuthMode(mode) {
    state.mode = mode;
    const registration = mode === "register";
    $("#auth-title").textContent = registration ? "Начните с чистого листа" : "С возвращением";
    $("#auth-description").textContent = registration ? "Создайте аккаунт и соберите важные дела в одном месте." : "Войдите, чтобы продолжить с того, где остановились.";
    $("#auth-submit span").textContent = registration ? "Создать аккаунт" : "Войти в пространство";
    $("#password").autocomplete = registration ? "new-password" : "current-password";
    for (const name of ["login", "register"]) {
      $(`#${name}-tab`).classList.toggle("active", name === mode);
      $(`#${name}-tab`).setAttribute("aria-pressed", String(name === mode));
    }
    showError("#auth-error");
  }

  function authPending(busy) {
    authBusy = busy;
    ["#auth-submit", "#login-tab", "#register-tab", "#username", "#password"].forEach((selector) => { $(selector).disabled = busy; });
    $("#auth-submit span").textContent = busy ? "Секунду…" : (state.mode === "register" ? "Создать аккаунт" : "Войти в пространство");
  }

  async function enterWorkspace() {
    const user = await api("/api/me");
    state.user = user;
    state.filter = "all";
    state.search = "";
    state.offset = 0;
    state.stats = null;
    $("#search").value = "";
    $("#account-name").textContent = user.username;
    $("#account-name").title = user.username;
    $("#user-avatar").textContent = user.username.charAt(0);
    const hour = new Date().getHours();
    const greeting = hour < 6 ? "Доброй ночи" : hour < 12 ? "Доброе утро" : hour < 18 ? "Добрый день" : "Добрый вечер";
    $("#greeting").textContent = `${greeting}, ${user.username}`;
    $("#today").textContent = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long", year: "numeric" }).format(new Date());
    $("#today").dateTime = new Date().toISOString();
    $("#boot-screen").hidden = true;
    $("#auth-screen").hidden = true;
    $("#app-screen").hidden = false;
    $("#password").value = "";
    updateFilters();
    renderStats();
    await refresh();
  }

  function updateFilters() {
    $$("[data-filter]").forEach((button) => {
      const selected = button.dataset.filter === state.filter;
      if (button.classList.contains("nav-button")) {
        button.classList.toggle("selected", selected);
        selected ? button.setAttribute("aria-current", "page") : button.removeAttribute("aria-current");
      }
      if (button.classList.contains("filter-tab")) {
        button.classList.toggle("active", selected);
        button.setAttribute("aria-pressed", String(selected));
      }
    });
  }

  async function setFilter(filter) {
    clearTimeout(searchTimer);
    state.filter = filter;
    state.search = $("#search").value.trim();
    state.offset = 0;
    updateFilters();
    await loadTasks();
  }

  function filteredCount() {
    return state.stats?.[state.filter === "all" ? "total" : state.filter];
  }

  function renderStats() {
    for (const key of ["total", "active", "completed"]) {
      $(`#stat-${key}`).textContent = state.stats?.[key] ?? "—";
      $(`#nav-${key}`).textContent = state.stats?.[key] ?? "—";
    }
    renderPageInfo();
  }

  function renderPageInfo() {
    const count = state.rows.length;
    const total = filteredCount();
    $("#list-count").textContent = state.search ? `${count}${state.hasNext ? "+" : ""}` : (total ?? "—");
    $("#page-number").textContent = Math.floor(state.offset / PAGE_SIZE) + 1;
    $("#previous-page").disabled = state.offset === 0;
    $("#next-page").disabled = !state.hasNext;
    $("#page-description").textContent = count ? `${state.offset + 1}–${state.offset + count}${!state.search && total !== undefined ? ` из ${total}` : " · результаты поиска"}` : "Нет задач для отображения";
  }

  async function loadStats() {
    try {
      state.stats = await api("/api/tasks/stats");
      renderStats();
    } catch (error) {
      if (!error.silent && state.user) {
        state.stats = null;
        renderStats();
        toast("Не удалось обновить счётчики. Нажмите «Обновить задачи».");
      }
    }
  }

  async function loadTasks() {
    const version = ++state.listVersion;
    state.controller?.abort();
    state.controller = new AbortController();
    const query = new URLSearchParams({ limit: String(PAGE_SIZE + 1), offset: String(state.offset) });
    if (state.filter !== "all") query.set("completed", String(state.filter === "completed"));
    if (state.search) query.set("q", state.search);
    $("#task-region").setAttribute("aria-busy", "true");
    $("#list-loading").hidden = false;
    $("#task-list").hidden = true;
    $("#empty-state").hidden = true;
    $("#list-error").hidden = true;
    $("#previous-page").disabled = true;
    $("#next-page").disabled = true;
    try {
      const result = await api(`/api/tasks?${query}`, { signal: state.controller.signal });
      if (version !== state.listVersion) return;
      if (!result.items.length && state.offset > 0) {
        state.offset = Math.max(0, state.offset - PAGE_SIZE);
        return await loadTasks();
      }
      state.rows = result.items.slice(0, PAGE_SIZE);
      state.hasNext = result.items.length > PAGE_SIZE;
      renderTasks();
    } catch (error) {
      if (version !== state.listVersion || error.name === "AbortError" || error.silent) return;
      state.rows = [];
      state.hasNext = false;
      $("#task-list").replaceChildren();
      $("#list-error span").textContent = error.message;
      $("#list-error").hidden = false;
      renderPageInfo();
      $("#page-description").textContent = "Список не загружен";
    } finally {
      if (version === state.listVersion) {
        $("#list-loading").hidden = true;
        $("#task-list").hidden = false;
        $("#task-region").setAttribute("aria-busy", "false");
      }
    }
  }

  async function refresh() { await Promise.all([loadTasks(), loadStats()]); }

  function renderTasks() {
    const fragment = document.createDocumentFragment();
    for (const task of state.rows) {
      const row = element("article", `task-row${task.completed ? " completed" : ""}`);
      row.dataset.taskId = task.id;
      const check = element("button", `task-check${task.completed ? " done" : ""}`);
      check.type = "button";
      check.disabled = task.completed;
      check.setAttribute("aria-label", task.completed ? `Выполнено: ${task.title}` : `Выполнить: ${task.title}`);
      if (task.completed) check.append(icon("check"));
      check.addEventListener("click", () => completeTask(task, check));
      const body = element("div", "task-body");
      const title = element("button", "task-title", task.title);
      title.type = "button";
      title.addEventListener("click", () => openDetail(task, title));
      body.append(title, element("p", "task-summary", task.description || "Без описания"));
      const date = element("time", "task-date", dateFormat.format(new Date(task.createdAt)));
      date.dateTime = task.createdAt;
      date.title = fullDateFormat.format(new Date(task.createdAt));
      const status = element("span", `status-pill${task.completed ? " completed" : ""}`, task.completed ? "Выполнено" : "В работе");
      const remove = element("button", "icon-button task-delete");
      remove.type = "button";
      remove.setAttribute("aria-label", `Удалить: ${task.title}`);
      remove.title = "Удалить задачу";
      remove.append(icon("trash"));
      remove.addEventListener("click", () => openDelete(task));
      row.append(check, body, date, status, remove);
      fragment.append(row);
    }
    $("#task-list").replaceChildren(fragment);
    const empty = state.rows.length === 0;
    $("#empty-state").hidden = !empty;
    if (empty) {
      const filtered = !!state.search || state.filter !== "all";
      $("#empty-title").textContent = filtered ? "Задачи не найдены" : "Здесь начинается порядок";
      $("#empty-description").textContent = state.search ? "Попробуйте другой запрос или сбросьте фильтры." : state.filter === "completed" ? "Выполненные задачи появятся здесь. Всё впереди!" : state.filter === "active" ? "Незавершённых задач нет. Можно немного выдохнуть." : "Добавьте первую задачу — освободите место для того, что действительно важно.";
      $("#empty-state [data-new-task]").hidden = filtered;
      $("#clear-filters").hidden = !filtered;
    }
    renderPageInfo();
  }

  function openCreate() {
    $("#task-form").reset();
    $("#description-count").textContent = "0 / 2000";
    showError("#task-error");
    $("#task-dialog").showModal();
  }

  async function completeTask(task, button) {
    button.disabled = true;
    try {
      await api(`/api/tasks/${task.id}/complete`, { method: "POST" });
      $("#detail-dialog").close();
      toast("Ещё одно дело сделано. Отличная работа!");
      await refresh();
    } catch (error) {
      if (!error.silent) {
        if ($("#detail-dialog").open) showError("#detail-error", error.message);
        else toast(error.message);
      }
    } finally { button.disabled = false; }
  }

  async function openDetail(task, button) {
    button.disabled = true;
    try {
      const current = await api(`/api/tasks/${task.id}`);
      state.detail = current;
      $("#detail-title").textContent = current.title;
      $("#detail-description").textContent = current.description || "У этой задачи пока нет описания.";
      $("#detail-date").textContent = `Создана ${fullDateFormat.format(new Date(current.createdAt))}`;
      $("#detail-status").textContent = current.completed ? "Выполнено" : "В работе";
      $("#detail-status").classList.toggle("completed", current.completed);
      $("#detail-complete").hidden = current.completed;
      showError("#detail-error");
      $("#detail-dialog").showModal();
    } catch (error) { if (!error.silent) toast(error.message); }
    finally { button.disabled = false; }
  }

  function openDelete(task) {
    state.deleting = task;
    $("#delete-task-name").textContent = task.title;
    showError("#delete-error");
    $("#delete-dialog").showModal();
  }

  $("#login-tab").addEventListener("click", () => setAuthMode("login"));
  $("#register-tab").addEventListener("click", () => setAuthMode("register"));
  $("#toggle-password").addEventListener("click", () => {
    const show = $("#password").type === "password";
    $("#password").type = show ? "text" : "password";
    $("#toggle-password").setAttribute("aria-label", show ? "Скрыть пароль" : "Показать пароль");
    $("#toggle-password").setAttribute("aria-pressed", String(show));
  });
  $("#auth-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (authBusy) return;
    const credentials = { username: $("#username").value.trim(), password: $("#password").value };
    const registration = state.mode === "register";
    showError("#auth-error");
    authPending(true);
    try {
      if (registration) await api("/api/auth/register", { method: "POST", body: credentials, authenticated: false });
      const result = await api("/api/auth/login", { method: "POST", body: credentials, authenticated: false });
      setToken(result.token);
      await enterWorkspace();
      if (registration) toast("Ваше пространство готово. Добавим первую задачу?");
    } catch (error) { if (!error.silent) { setToken(""); showError("#auth-error", error.message); } }
    finally { authPending(false); }
  });
  $("#logout").addEventListener("click", () => { showAuth(); $("#username").focus(); });
  $$("[data-filter]").forEach((button) => button.addEventListener("click", () => setFilter(button.dataset.filter)));
  $$("[data-new-task]").forEach((button) => button.addEventListener("click", openCreate));
  $$("[data-close-dialog]").forEach((button) => button.addEventListener("click", () => button.closest("dialog").close()));
  $$("dialog").forEach((dialog) => dialog.addEventListener("click", (event) => { if (event.target === dialog) { const box = dialog.getBoundingClientRect(); if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) dialog.close(); } }));
  $("#search").addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { state.search = $("#search").value.trim(); state.offset = 0; loadTasks(); }, 250);
  });
  $("#clear-filters").addEventListener("click", () => { $("#search").value = ""; setFilter("all"); });
  $("#refresh").addEventListener("click", () => refresh());
  $("#retry").addEventListener("click", () => refresh());
  $("#previous-page").addEventListener("click", () => { state.offset = Math.max(0, state.offset - PAGE_SIZE); loadTasks(); });
  $("#next-page").addEventListener("click", () => { state.offset += PAGE_SIZE; loadTasks(); });
  $("#task-description").addEventListener("input", () => { $("#description-count").textContent = `${$("#task-description").value.length} / 2000`; });
  $("#task-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const title = $("#task-title").value.trim();
    if (!title) { showError("#task-error", "Добавьте название задачи."); $("#task-title").focus(); return; }
    if ($("#create-submit").disabled) return;
    $("#create-submit").disabled = true;
    $("#create-submit span").textContent = "Создаём…";
    showError("#task-error");
    try {
      await api("/api/tasks", { method: "POST", body: { title, description: $("#task-description").value } });
      $("#task-dialog").close();
      state.filter = "all";
      state.search = "";
      state.offset = 0;
      $("#search").value = "";
      updateFilters();
      toast("Задача добавлена. Первый шаг уже сделан.");
      await refresh();
    } catch (error) { if (!error.silent) showError("#task-error", error.message); }
    finally { $("#create-submit").disabled = false; $("#create-submit span").textContent = "Создать задачу"; }
  });
  $("#detail-complete").addEventListener("click", () => { if (state.detail) completeTask(state.detail, $("#detail-complete")); });
  $("#detail-delete").addEventListener("click", () => { if (state.detail) openDelete(state.detail); });
  $("#confirm-delete").addEventListener("click", async () => {
    if (!state.deleting || $("#confirm-delete").disabled) return;
    $("#confirm-delete").disabled = true;
    showError("#delete-error");
    try {
      await api(`/api/tasks/${state.deleting.id}`, { method: "DELETE" });
      $("#delete-dialog").close();
      $("#detail-dialog").close();
      toast("Задача удалена.");
      await refresh();
    } catch (error) { if (!error.silent) showError("#delete-error", error.message); }
    finally { $("#confirm-delete").disabled = false; }
  });

  (async function boot() {
    try { state.token = sessionStorage.getItem(SESSION_KEY) || ""; } catch { /* Вход доступен без sessionStorage. */ }
    if (!state.token) { showAuth(); return; }
    try { await enterWorkspace(); }
    catch (error) { if (!error.silent) showAuth(error.message); }
  })();
})();
