(() => {
    'use strict';

    const dom = (id) => document.getElementById(id);
    const application = dom('workbench');
    const messages = dom('messages');
    const input = dom('prompt-input');
    const assetVersion = '20260923-plan1';
    const editorModal = bootstrap.Modal.getOrCreateInstance(dom('editor-modal'));

    const model = {
        sessionId: localStorage.getItem('avr.session'),
        locale: localStorage.getItem('avr.locale') || preferredLocale(),
        theme: localStorage.getItem('avr.theme') || 'light',
        translations: {},
        fallbackTranslations: {},
        busy: false,
        historyLoaded: false,
        files: [],
        entries: [],
        artifacts: [],
        selectedPath: null,
        selectedDirectory: false,
        selectedModel: localStorage.getItem('avr.model'),
        modelCatalog: [],
        webSearch: localStorage.getItem('avr.webSearch') !== 'false',
        webSearchSaved: localStorage.getItem('avr.webSearch') !== null,
        activeEditorPath: null,
        previewUrl: null,
        runtimeState: 'ready',
        workspaceRefreshTimer: null,
        expandedDirectories: new Set(['/workspace']),
        workspaceFilter: '',
        workspaceNodes: new Map(),
        workspaceActivity: new Map(),
        plan: null
    };

    function preferredLocale() {
        return navigator.language && navigator.language.toLowerCase().startsWith('zh')
            ? 'zh-CN'
            : 'en-US';
    }

    function resizeComposer() {
        input.style.height = 'auto';
        const maximum = Number.parseFloat(getComputedStyle(input).maxHeight) || 224;
        const height = Math.min(input.scrollHeight, maximum);
        input.style.height = `${height}px`;
        input.style.overflowY = input.scrollHeight > maximum ? 'auto' : 'hidden';
    }

    function updateSendState() {
        dom('send').disabled = model.busy || input.value.trim().length === 0;
    }

    function selectedModelProfile() {
        return model.modelCatalog.find((profile) => profile.id === model.selectedModel);
    }

    function webSearchEnabledForSelection() {
        const profile = selectedModelProfile();
        return Boolean(profile && profile.webSearchSupported && model.webSearch);
    }

    function syncWebSearchControl() {
        const toggle = dom('web-search-toggle');
        const label = dom('web-search-label');
        const profile = selectedModelProfile();
        const supported = Boolean(profile && profile.webSearchSupported);
        toggle.disabled = model.busy || !supported;
        toggle.checked = supported && model.webSearch;
        const hintKey = supported
            ? 'composer.webSearchHint' : 'composer.webSearchUnsupported';
        label.title = t(hintKey);
        label.setAttribute('aria-label', t(hintKey));
    }

    function setComposerBusy(busy) {
        model.busy = busy;
        dom('composer').classList.toggle('is-busy', busy);
        dom('composer').setAttribute('aria-busy', String(busy));
        input.readOnly = busy;
        dom('model-select').disabled = busy;
        syncWebSearchControl();
        updateSendState();
    }

    async function loadTranslations() {
        const [fallbackResponse, selectedResponse] = await Promise.all([
            fetch(`/i18n/en-US.json?v=${assetVersion}`),
            fetch(`/i18n/${model.locale}.json?v=${assetVersion}`)
        ]);
        model.fallbackTranslations = fallbackResponse.ok
            ? await fallbackResponse.json()
            : {};
        model.translations = selectedResponse.ok
            ? await selectedResponse.json()
            : model.fallbackTranslations;
    }

    function t(key, values = {}) {
        let text = model.translations[key]
            || model.fallbackTranslations[key]
            || key;
        Object.entries(values).forEach(([name, value]) => {
            text = text.replaceAll(`{${name}}`, String(value));
        });
        return text;
    }

    function applyI18n() {
        document.documentElement.lang = model.locale;
        document.title = t('app.title');
        dom('locale-label').textContent = model.locale === 'zh-CN' ? '中文' : 'English';

        document.querySelectorAll('[data-i18n]').forEach((element) => {
            element.textContent = t(element.dataset.i18n);
        });
        document.querySelectorAll('[data-i18n-placeholder]').forEach((element) => {
            element.placeholder = t(element.dataset.i18nPlaceholder);
        });
        document.querySelectorAll('[data-i18n-aria-label]').forEach((element) => {
            element.setAttribute('aria-label', t(element.dataset.i18nAriaLabel));
        });
        document.querySelectorAll('[data-i18n-title]').forEach((element) => {
            element.title = t(element.dataset.i18nTitle);
            if (element.classList.contains('icon-button')) {
                element.setAttribute('aria-label', element.title);
            }
        });

        updateThemeLabel();
        refreshDynamicTranslations();
    }

    function refreshDynamicTranslations() {
        document.querySelectorAll('[data-message-role]').forEach((element) => {
            element.textContent = t(element.dataset.messageRole === 'user'
                ? 'message.you'
                : 'message.agent');
        });
        document.querySelectorAll('.run-activity').forEach((element) => {
            renderRunActivity(element);
        });
        document.querySelectorAll('[data-run-error]').forEach((element) => {
            element.textContent = runErrorMessage(element.dataset.runError);
        });
        document.querySelectorAll('.reply-status').forEach((element) => {
            element.textContent = t(element.dataset.statusKey);
        });
        document.querySelectorAll('.reasoning-summary-label').forEach((element) => {
            element.textContent = t('message.reasoning', {count: element.dataset.count});
        });
        document.querySelectorAll('.reasoning-item-title').forEach((element) => {
            element.textContent = t(element.dataset.labelKey, {
                round: element.dataset.round,
                part: element.dataset.part
            });
        });
        document.querySelectorAll('.reply-elapsed').forEach((element) => {
            element.textContent = t('message.elapsed', {
                seconds: Math.floor((Date.now() - Number(element.dataset.startedAt)) / 1000)
            });
        });
        renderPlanPanel();
        renderFiles();
        renderArtifacts();
        setRuntimeState(model.runtimeState);
    }

    function applyTheme(choice) {
        model.theme = choice;
        localStorage.setItem('avr.theme', choice);
        const resolved = choice === 'system'
            ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
            : choice;
        document.documentElement.setAttribute('data-bs-theme', resolved);
        updateThemeLabel();
    }

    function updateThemeLabel() {
        dom('theme-label').textContent = t(`theme.${model.theme}`);
        dom('theme-icon').className = `bi ${({
            light: 'bi-sun',
            dark: 'bi-moon-stars',
            system: 'bi-circle-half'
        })[model.theme]}`;
    }

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, (character) => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        })[character]);
    }

    function markdown(value) {
        const source = String(value || '');
        if (!window.marked || !window.DOMPurify) {
            return escapeHtml(source);
        }
        const rendered = marked.parse(source, {
            gfm: true,
            breaks: true
        });
        const safeHtml = DOMPurify.sanitize(rendered, {
            USE_PROFILES: {html: true}
        });
        const template = document.createElement('template');
        template.innerHTML = safeHtml;
        template.content.querySelectorAll('table').forEach((table) => {
            const wrapper = document.createElement('div');
            wrapper.className = 'markdown-table-scroll';
            table.replaceWith(wrapper);
            wrapper.append(table);
        });
        return template.innerHTML;
    }

    function splitThinking(value) {
        const source = String(value || '');
        const lower = source.toLowerCase();
        let visible = '';
        let currentReasoning = '';
        const reasoningSegments = [];
        let position = 0;
        let inThink = false;
        while (position < source.length) {
            const next = lower.indexOf('<', position);
            if (next < 0) {
                if (inThink) {
                    currentReasoning += source.slice(position);
                } else {
                    visible += source.slice(position);
                }
                break;
            }
            if (inThink) {
                currentReasoning += source.slice(position, next);
            } else {
                visible += source.slice(position, next);
            }
            const tag = /^<\/?think>/i.exec(source.slice(next));
            if (tag) {
                if (tag[0].startsWith('</')) {
                    if (currentReasoning.trim()) {
                        reasoningSegments.push(currentReasoning);
                    }
                    currentReasoning = '';
                    inThink = false;
                } else {
                    inThink = true;
                }
                position = next + tag[0].length;
                continue;
            }
            const rest = lower.slice(next);
            if ('<think>'.startsWith(rest) || '</think>'.startsWith(rest)) {
                break;
            }
            if (inThink) {
                currentReasoning += source[next];
            } else {
                visible += source[next];
            }
            position = next + 1;
        }
        if (currentReasoning.trim()) {
            reasoningSegments.push(currentReasoning);
        }
        return {visible, reasoningSegments, inThink};
    }

    function visibleText(value) {
        return splitThinking(value).visible.trimStart();
    }

    function currentTime() {
        return new Intl.DateTimeFormat(model.locale, {
            hour: '2-digit',
            minute: '2-digit'
        }).format(new Date());
    }

    function createTurn(role, content = '') {
        dom('welcome')?.remove();

        const article = document.createElement('article');
        article.className = `message-turn ${role === 'user' ? 'is-user' : 'is-assistant'}`;

        const avatar = document.createElement('div');
        avatar.className = 'message-avatar';
        avatar.setAttribute('aria-hidden', 'true');

        const contentColumn = document.createElement('div');
        contentColumn.className = 'min-w-0';

        const meta = document.createElement('div');
        meta.className = 'message-meta';
        const name = document.createElement('strong');
        name.dataset.messageRole = role;
        name.textContent = t(role === 'user' ? 'message.you' : 'message.agent');
        const time = document.createElement('time');
        time.textContent = currentTime();
        meta.append(name, time);

        const body = document.createElement('div');
        body.className = 'message-body';
        if (role === 'user') {
            body.textContent = content;
        } else if (content === '…') {
            showReplyLoading(body);
        } else {
            body.innerHTML = markdown(visibleText(content));
        }

        contentColumn.append(meta, body);
        avatar.innerHTML = role === 'user'
            ? '<i class="bi bi-person"></i>'
            : '<img src="/assets/brand/avr-icon-64.png" alt="">';
        article.append(avatar, contentColumn);
        messages.append(article);
        scrollMessages();

        return {article, body, contentColumn};
    }

    function showReplyLoading(body, streamState) {
        const key = `message.${streamState?.phase || 'waiting'}`;
        const startedAt = streamState?.startedAt || Date.now();
        body.innerHTML = `<span class="reply-loading" role="status">`
            + `<span class="reply-pulse" aria-hidden="true"><i></i><i></i><i></i></span>`
            + `<span class="reply-status" data-status-key="${key}">${escapeHtml(t(key))}</span>`
            + `<span class="reply-elapsed" data-started-at="${startedAt}"></span>`
            + `</span>`;
        updateReplyElapsed(body);
    }

    function updateReplyElapsed(body) {
        const elapsed = body.querySelector('.reply-elapsed');
        if (elapsed) {
            elapsed.textContent = t('message.elapsed', {
                seconds: Math.floor((Date.now() - Number(elapsed.dataset.startedAt)) / 1000)
            });
        }
    }

    function updateReasoningPanel(body, segments, finished) {
        const column = body.parentElement;
        let panel = column.querySelector('.reasoning-panel');
        if (!segments.length) {
            panel?.remove();
            return;
        }
        if (!panel) {
            panel = document.createElement('details');
            panel.className = 'reasoning-panel';
            panel.open = !finished;
            panel.innerHTML = `<summary><i class="bi bi-stars" aria-hidden="true"></i>`
                + `<span class="reasoning-summary-label"></span>`
                + `<i class="bi bi-chevron-down reasoning-chevron" aria-hidden="true"></i></summary>`
                + `<div class="reasoning-content"></div>`;
            column.insertBefore(panel, body);
        }
        const label = panel.querySelector('.reasoning-summary-label');
        label.dataset.count = String(segments.length);
        label.textContent = t('message.reasoning', {count: segments.length});
        const content = panel.querySelector('.reasoning-content');
        content.replaceChildren();
        segments.forEach((segment) => {
            const item = document.createElement('section');
            item.className = 'reasoning-item';
            const title = document.createElement('div');
            title.className = 'reasoning-item-title';
            title.dataset.labelKey = segment.labelKey;
            title.dataset.round = String(segment.round);
            title.dataset.part = String(segment.part || 1);
            title.textContent = t(segment.labelKey, {
                round: segment.round,
                part: segment.part || 1
            });
            const text = document.createElement('div');
            text.className = 'reasoning-item-content';
            text.textContent = segment.text;
            item.append(title, text);
            content.append(item);
        });
        content.scrollTop = content.scrollHeight;
        if (finished) {
            panel.open = false;
        }
    }

    function renderStreamReply(body, streamState) {
        const segments = [];
        streamState.rounds.forEach((round, index) => {
            const number = index + 1;
            if (round.reasoning.trim()) {
                segments.push({labelKey: 'message.reasoningRound',
                    round: number, text: round.reasoning});
            }
            const parts = splitThinking(round.text);
            parts.reasoningSegments.forEach((text, part) => {
                segments.push({labelKey: 'message.reasoningPart',
                    round: number, part: part + 1, text});
            });
            if (round.toolCallCount > 0 && parts.visible.trim()) {
                segments.push({labelKey: 'message.progressRound',
                    round: number, text: parts.visible.trim()});
            }
        });
        updateReasoningPanel(body, segments, streamState.done);
        const lastRound = streamState.rounds.at(-1);
        const visible = lastRound && lastRound.toolCallCount !== null
            && lastRound.toolCallCount > 0
            ? '' : splitThinking(lastRound?.text).visible;
        if (!visible.trim()) {
            showReplyLoading(body, streamState);
            return;
        }
        body.innerHTML = markdown(visible);
        if (!streamState.done) {
            const progress = document.createElement('div');
            progress.className = 'reply-progress';
            progress.innerHTML = `<span class="reply-pulse" aria-hidden="true">`
                + `<i></i><i></i><i></i></span>`
                + `<span class="reply-status" data-status-key="message.${streamState.phase}">`
                + escapeHtml(t(`message.${streamState.phase}`)) + `</span>`
                + `<span class="reply-elapsed" data-started-at="${streamState.startedAt}"></span>`;
            body.append(progress);
            updateReplyElapsed(body);
        }
    }

    function currentModelRound(streamState) {
        if (!streamState.rounds.length) {
            streamState.rounds.push({text: '', reasoning: '', toolCallCount: null});
        }
        return streamState.rounds[streamState.rounds.length - 1];
    }

    function scrollMessages() {
        messages.scrollTop = messages.scrollHeight;
    }

    function createRunActivity(contentColumn) {
        const activityElement = document.createElement('details');
        activityElement.className = 'run-activity';
        activityElement.open = true;
        activityElement.hidden = true;
        activityElement.dataset.activity = 'true';
        activityElement.activityModel = {
            records: [],
            recordsById: new Map(),
            rowsById: new Map(),
            status: 'running',
            steps: null
        };
        activityElement.addEventListener('toggle', () => {
            if (!activityElement.open) {
                activityElement.querySelectorAll('[data-bs-toggle="tool-result"]')
                    .forEach((element) => bootstrap.Popover.getInstance(element)?.hide());
            }
        });
        const body = contentColumn.querySelector('.message-body');
        contentColumn.insertBefore(activityElement, body);
        renderRunActivity(activityElement);
        return activityElement;
    }

    function renderRunActivity(activityElement) {
        const activity = activityElement.activityModel;
        if (!activity) {
            return;
        }
        const expanded = activityElement.open;
        activityElement.querySelectorAll('[data-bs-toggle="tool-result"]').forEach((element) => {
            element.cleanupPopover?.();
        });
        activityElement.replaceChildren();
        const summary = document.createElement('summary');
        summary.className = 'tool-step-summary';
        summary.innerHTML = `<i class="bi bi-chevron-down"></i>`
            + `<span>${escapeHtml(t('activity.summary', {count: activity.records.length}))}</span>`;
        const list = document.createElement('div');
        list.className = 'tool-step-list';
        activity.rowsById.clear();
        activity.records.forEach((record) => {
            const row = createToolStep(record);
            activity.rowsById.set(record.callId, row);
            list.append(row);
        });
        activityElement.append(summary, list);
        activityElement.open = expanded;
        list.scrollTop = list.scrollHeight;
    }

    function updateToolActivity(activityElement, event) {
        const activity = activityElement.activityModel;
        const attributes = event.attributes || {};
        const callId = attributes.toolCallId || `${event.sequence}`;
        let record = activity.recordsById.get(callId);

        if (!record) {
            record = {...event, callId, startedAt: event.occurredAt};
            activity.recordsById.set(callId, record);
            activity.records.push(record);
        } else {
            Object.assign(record, event);
            if (event.type !== 'tool.started') {
                record.completedAt = event.occurredAt;
            }
        }

        activityElement.hidden = false;
        const list = activityElement.querySelector('.tool-step-list');
        const previousRow = activity.rowsById.get(callId);
        const wasAtBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 32;
        const nextRow = createToolStep(record);
        if (previousRow) {
            previousRow.querySelector('[data-bs-toggle="tool-result"]')?.cleanupPopover?.();
            previousRow.replaceWith(nextRow);
        } else {
            list.append(nextRow);
        }
        activity.rowsById.set(callId, nextRow);
        activityElement.querySelector('.tool-step-summary span').textContent =
            t('activity.summary', {count: activity.records.length});
        if (wasAtBottom) {
            list.scrollTop = list.scrollHeight;
        }
    }

    function isPlanEvent(event) {
        return (event.attributes?.toolName || event.detail) === 'plan.manage';
    }

    function resetPlanPanel() {
        model.plan = null;
        renderPlanPanel();
    }

    function updatePlanPanel(event) {
        if (!isPlanEvent(event)) {
            return false;
        }
        if (event.type === 'tool.started') {
            return true;
        }
        if (event.type === 'tool.completed') {
            try {
                const snapshot = JSON.parse(event.attributes?.resultPreview || '');
                if (Array.isArray(snapshot.steps)) {
                    model.plan = snapshot;
                }
            } catch (error) {
                // 保留上一次有效快照，避免一次不完整预览清空计划。
            }
            renderPlanPanel();
        }
        return true;
    }

    function renderPlanPanel() {
        const panel = dom('task-plan');
        const list = dom('task-plan-list');
        const steps = model.plan?.steps || [];
        panel.hidden = steps.length === 0;
        list.replaceChildren();
        if (!steps.length) {
            dom('task-plan-progress').textContent = '';
            return;
        }
        const done = steps.filter((step) => step.status === 'done' && step.verified !== false).length;
        dom('task-plan-progress').textContent = t('plan.progress', {
            done,
            total: steps.length
        });
        steps.forEach((step) => {
            const completed = step.status === 'done' && step.verified !== false;
            const active = step.status === 'in_progress';
            const item = document.createElement('label');
            item.className = `task-plan-item${completed ? ' is-complete' : ''}`
                + `${active ? ' is-active' : ''}`;
            item.setAttribute('role', 'listitem');
            const checkbox = document.createElement('input');
            checkbox.className = 'form-check-input';
            checkbox.type = 'checkbox';
            checkbox.checked = completed;
            checkbox.disabled = true;
            checkbox.indeterminate = active;
            checkbox.setAttribute('aria-label', step.description || step.id || t('plan.item'));
            const text = document.createElement('span');
            text.className = 'task-plan-description';
            text.textContent = step.description || step.id || t('plan.item');
            item.append(checkbox, text);
            list.append(item);
        });
        list.scrollTop = list.scrollHeight;
    }

    function createToolStep(event) {
        const row = document.createElement('div');
        const finished = event.type !== 'tool.started';
        const failed = event.type === 'tool.failed';
        row.className = `tool-step${failed ? ' is-error' : ''}`;
        const icon = document.createElement('i');
        icon.className = failed ? 'bi bi-x-lg tool-step-icon'
            : finished ? 'bi bi-check-lg tool-step-icon'
                : 'bi bi-arrow-repeat tool-step-icon is-spinning';
        row.append(icon);

        const name = toolName(event);
        if (!finished) {
            const label = document.createElement('span');
            label.className = 'tool-step-label';
            label.textContent = name;
            row.append(label);
            return row;
        }

        const resultLink = document.createElement('button');
        resultLink.type = 'button';
        resultLink.className = 'tool-step-name';
        resultLink.dataset.bsToggle = 'tool-result';
        resultLink.textContent = name;
        resultLink.setAttribute('aria-label', t('activity.resultFor', {tool: name}));
        row.append(resultLink);

        const rows = event.attributes?.rows;
        const duration = formatToolDuration(event.attributes?.durationMillis);
        const count = Number.isInteger(rows)
            ? `${t('activity.rows', {count: rows})} · `
            : '';
        const meta = document.createElement('span');
        meta.className = 'tool-step-meta';
        meta.textContent = `${count}${duration}`;
        row.append(meta);
        attachResultPopover(resultLink, event);
        return row;
    }

    function attachResultPopover(link, event) {
        const popover = new bootstrap.Popover(link, {
            container: 'body',
            trigger: 'manual',
            placement: 'right',
            customClass: 'avr-tool-popover',
            html: true,
            content: () => createResultPreview(event)
        });
        let closeTimer;
        const cancelClose = () => clearTimeout(closeTimer);
        const scheduleClose = () => {
            cancelClose();
            closeTimer = setTimeout(() => popover.hide(), 350);
        };
        const show = () => {
            cancelClose();
            popover.show();
        };
        link.addEventListener('mouseenter', show);
        link.addEventListener('mouseleave', scheduleClose);
        link.addEventListener('focus', show);
        link.addEventListener('blur', scheduleClose);
        link.addEventListener('inserted.bs.popover', () => {
            const tip = document.getElementById(link.getAttribute('aria-describedby'));
            if (tip) {
                tip.addEventListener('mouseenter', cancelClose);
                tip.addEventListener('mouseleave', scheduleClose);
            }
        });
        link.cleanupPopover = () => {
            cancelClose();
            popover.dispose();
        };
    }

    function formatToolDuration(value) {
        const millis = Number(value) || 0;
        return millis < 1000 ? `${millis} ms` : `${(millis / 1000).toFixed(1)}s`;
    }

    function createResultPreview(event) {
        const type = String(event.attributes?.displayType || 'TEXT').toUpperCase();
        const value = event.attributes?.resultPreview ?? '';
        const root = document.createElement('div');
        root.className = 'tool-result-preview';
        if (value === '') {
            root.textContent = t('activity.emptyResult');
            return root;
        }
        if (type === 'TABLE') {
            const tableData = parseTableData(value);
            if (tableData) {
                root.append(createResultTable(tableData));
                return root;
            }
        }
        const content = document.createElement(type === 'TEXT' || type === 'MARKDOWN' ? 'div' : 'pre');
        content.className = type === 'TEXT' ? 'tool-result-text' : 'tool-result-code';
        if (type === 'MARKDOWN') {
            content.innerHTML = markdown(value);
        } else if (type === 'JSON' || type === 'TREE') {
            try {
                content.textContent = JSON.stringify(JSON.parse(value), null, 2);
            } catch (error) {
                content.textContent = value;
            }
        } else {
            content.textContent = value;
        }
        root.append(content);
        return root;
    }

    function parseTableData(value) {
        try {
            const parsed = typeof value === 'string' ? JSON.parse(value) : value;
            const rows = Array.isArray(parsed) ? parsed
                : Object.values(parsed || {}).find((item) => Array.isArray(item));
            return Array.isArray(rows) && rows.some((item) => item && typeof item === 'object')
                ? rows : null;
        } catch (error) {
            return null;
        }
    }

    function createResultTable(rows) {
        const wrapper = document.createElement('div');
        wrapper.className = 'tool-result-table-wrap';
        const table = document.createElement('table');
        table.className = 'tool-result-table';
        const columns = Array.from(new Set(rows.slice(0, 50)
            .flatMap((item) => Object.keys(item || {})))).slice(0, 12);
        const head = document.createElement('thead');
        const heading = document.createElement('tr');
        columns.forEach((column) => {
            const cell = document.createElement('th');
            cell.textContent = column;
            heading.append(cell);
        });
        head.append(heading);
        const body = document.createElement('tbody');
        rows.slice(0, 50).forEach((item) => {
            const line = document.createElement('tr');
            columns.forEach((column) => {
                const cell = document.createElement('td');
                const value = item?.[column];
                cell.textContent = value != null && typeof value === 'object'
                    ? JSON.stringify(value) : String(value ?? '');
                line.append(cell);
            });
            body.append(line);
        });
        table.append(head, body);
        wrapper.append(table);
        return wrapper;
    }

    function toolName(event) {
        const name = event.attributes?.toolName || event.detail || '';
        const operation = event.attributes?.arguments?.op;
        if (typeof operation === 'string') {
            const operationKey = `tool.${name}.${operation}`;
            const operationLabel = t(operationKey);
            if (operationLabel !== operationKey) {
                return operationLabel;
            }
        }
        const key = `tool.${name}`;
        const localized = t(key);
        if (localized !== key) {
            return localized;
        }
        return event.attributes?.displayName || name;
    }

    async function ensureSession() {
        if (model.sessionId) {
            const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}`);
            if (response.ok) {
                applySessionState(await response.json());
                return;
            }
            model.sessionId = null;
            localStorage.removeItem('avr.session');
        }

        const response = await fetch('/api/chat/sessions', {method: 'POST'});
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        applySessionState(await response.json());
    }

    function applySessionState(state) {
        model.sessionId = state.sessionId;
        localStorage.setItem('avr.session', model.sessionId);
        dom('workspace-name').removeAttribute('data-i18n');
        dom('workspace-name').textContent = state.workspaceId;
        dom('workspace-session-label').removeAttribute('data-i18n');
        dom('workspace-session-label').textContent = state.workspaceId || state.sessionId;
        model.files = state.files || [];
        model.entries = state.entries || [];
        model.artifacts = state.artifacts || [];
        renderFiles();
        renderArtifacts();

        if (!model.historyLoaded && !messages.querySelector('.message-turn')) {
            if ((state.runs || []).length > 0) {
                (state.runs || []).forEach(restoreRun);
            } else {
                (state.history || []).forEach((message) => {
                    createTurn(message.role, message.content);
                });
            }
        }
        model.historyLoaded = true;
    }

    function restoreRun(run) {
        resetPlanPanel();
        createTurn('user', run.prompt);
        const assistant = createTurn('assistant', '…');
        const activity = createRunActivity(assistant.contentColumn);
        const rounds = (run.modelRounds || []).map((round) => ({
            text: round.text || '',
            reasoning: round.reasoning || '',
            toolCallCount: round.toolCallCount ?? null
        }));
        if (!rounds.length) {
            rounds.push({text: run.content || '', reasoning: run.reasoning || '',
                toolCallCount: 0});
        } else if (run.status === 'completed' && !rounds.at(-1).text) {
            rounds.at(-1).text = run.content || '';
        }
        const streamState = {
            rounds,
            done: run.status === 'completed',
            phase: 'thinking',
            startedAt: Date.now()
        };
        renderStreamReply(assistant.body, streamState);
        if (streamState.done) {
            assistant.body.innerHTML = markdown(visibleText(run.content) || t('message.empty'));
        }
        let after = 0;
        (run.events || []).forEach((event) => {
            after = Math.max(after, event.sequence || 0);
            if (event.type?.startsWith('tool.') && !updatePlanPanel(event)) {
                updateToolActivity(activity, event);
            }
        });
        activity.activityModel.status = run.status === 'completed'
            ? 'done' : run.status;
        activity.activityModel.steps = run.steps;
        renderRunActivity(activity);

        if (run.status === 'running') {
            setComposerBusy(true);
            setRuntimeState('running');
            resumeRun(run.runId, after, assistant.body, activity, streamState);
        } else if (run.status === 'failed') {
            renderRunFailure(assistant.body, run.error);
        }
    }

    async function refreshSession() {
        if (!model.sessionId) {
            return ensureSession();
        }
        const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        applySessionState(await response.json());
    }

    async function refreshWorkspace() {
        if (!model.sessionId) {
            return;
        }
        const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}`);
        if (!response.ok) {
            return;
        }
        const state = await response.json();
        model.files = state.files || [];
        model.entries = state.entries || [];
        model.artifacts = state.artifacts || [];
        renderFiles();
        renderArtifacts();
        if (model.previewUrl && !dom('preview-frame').classList.contains('d-none')) {
            const separator = model.previewUrl.includes('?') ? '&' : '?';
            dom('preview-frame').src = `${model.previewUrl}${separator}avrReload=${Date.now()}`;
        }
    }

    function scheduleWorkspaceRefresh(event) {
        const tool = event.attributes?.toolName || '';
        const operation = event.attributes?.arguments?.op;
        const changed = (tool === 'file.op' && operation !== 'list'
            && operation !== 'read' && operation !== 'search')
            || (tool === 'directory.manage' && operation !== 'list')
            || tool === 'artifact.commit'
            || new Set(['file.write', 'file.append', 'file.replace',
                'file.copy', 'file.move', 'file.delete']).has(tool);
        if (event.type !== 'tool.completed' || !changed) {
            return;
        }
        clearTimeout(model.workspaceRefreshTimer);
        model.workspaceRefreshTimer = setTimeout(() => {
            refreshWorkspace().catch(() => undefined);
        }, 180);
    }

    function updateWorkspaceActivity(event) {
        const argumentsValue = event.attributes?.arguments || {};
        const paths = [argumentsValue.path, argumentsValue.source, argumentsValue.target]
            .filter((path) => typeof path === 'string' && path.startsWith('/workspace'));
        if (!paths.length) {
            return;
        }
        const status = event.type === 'tool.started' ? 'working'
            : event.type === 'tool.completed' ? 'done' : null;
        if (!status) {
            return;
        }
        paths.forEach((path) => model.workspaceActivity.set(path, status));
        renderFiles();
        if (status === 'done') {
            setTimeout(() => {
                paths.forEach((path) => model.workspaceActivity.delete(path));
                renderFiles();
            }, 2400);
        }
    }

    function createTree(paths, entries = []) {
        const root = {
            name: 'workspace',
            path: '/workspace',
            directory: true,
            size: null,
            children: new Map()
        };

        const entryByPath = new Map(entries.map((entry) => [entry.path, entry]));
        const values = [...new Set([...entries.map((entry) => entry.path), ...paths])];
        const directoryPaths = new Set(entries
            .filter((entry) => entry.type === 'directory')
            .map((entry) => entry.path));
        values.forEach((path) => {
            const relative = path.replace(/^\/workspace\/?/, '');
            if (!relative) {
                return;
            }
            let node = root;
            relative.split('/').forEach((part, index, parts) => {
                const currentPath = `${node.path}/${part}`;
                const isDirectory = index < parts.length - 1
                    || directoryPaths.has(currentPath);
                if (!node.children.has(part)) {
                    node.children.set(part, {
                        name: part,
                        path: currentPath,
                        directory: isDirectory,
                        size: null,
                        children: new Map()
                    });
                }
                node = node.children.get(part);
                node.directory = node.directory || isDirectory;
                const entry = entryByPath.get(currentPath);
                node.size = entry?.size ?? node.size;
                node.entryType = entry?.type;
            });
        });
        return root;
    }

    function renderFiles() {
        const container = dom('file-tree');
        dom('file-count').textContent = String(model.files.length);
        container.innerHTML = '';
        model.workspaceNodes.clear();

        const root = createTree(model.files, model.entries);
        const list = document.createElement('ul');
        list.className = 'tree-list';
        list.setAttribute('role', 'group');
        const renderedRoot = renderTreeNode(root, true);
        if (renderedRoot) {
            list.append(renderedRoot);
        }
        container.append(list);
        if (model.files.length === 0 && model.entries.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'tree-empty';
            empty.textContent = t('workspace.empty');
            container.append(empty);
        } else if (!renderedRoot) {
            const empty = document.createElement('div');
            empty.className = 'tree-empty';
            empty.textContent = t('workspace.noMatches');
            container.append(empty);
        }
        renderWorkspaceBreadcrumb();
        renderWorkspaceSelection();
    }

    function renderTreeNode(node, isRoot = false) {
        const visibleChildren = sortedChildren(node)
            .filter((child) => treeNodeMatchesFilter(child));
        const query = model.workspaceFilter.trim().toLocaleLowerCase(model.locale);
        if (!isRoot && query && !node.name.toLocaleLowerCase(model.locale).includes(query)
                && visibleChildren.length === 0) {
            return null;
        }
        model.workspaceNodes.set(node.path, node);
        const item = document.createElement('li');
        item.setAttribute('role', 'treeitem');
        if (node.directory) {
            item.setAttribute('aria-expanded', String(query || model.expandedDirectories.has(node.path)));
        }

        const row = document.createElement('button');
        row.type = 'button';
        row.className = `tree-row${model.selectedPath === node.path ? ' is-selected' : ''}`;
        row.dataset.path = node.path;
        row.dataset.fileKind = fileKind(node);
        row.tabIndex = model.selectedPath === node.path || (!model.selectedPath && isRoot) ? 0 : -1;
        row.setAttribute('aria-label', node.directory
            ? t('workspace.directoryLabel', {name: node.name})
            : t('workspace.fileLabel', {name: node.name, size: formatBytes(node.size)}));

        const expanded = Boolean(query) || model.expandedDirectories.has(node.path);
        const chevron = document.createElement(node.directory ? 'i' : 'span');
        chevron.className = node.directory
            ? `bi ${expanded ? 'bi-chevron-down' : 'bi-chevron-right'} tree-chevron`
            : 'tree-chevron';
        const icon = document.createElement('i');
        icon.className = `bi ${fileIcon(node)} tree-icon`;
        const name = document.createElement('span');
        name.className = 'tree-name';
        name.textContent = node.name;
        const size = document.createElement('span');
        size.className = 'tree-size';
        size.textContent = node.directory ? '' : formatBytes(node.size);
        const state = document.createElement('span');
        const activity = model.workspaceActivity.get(node.path);
        state.className = activity ? `tree-state${activity === 'working' ? ' is-working' : ''}` : '';
        state.setAttribute('aria-hidden', 'true');
        row.append(chevron, icon, name, size, state);

        row.addEventListener('click', () => selectTreeNode(node));
        row.addEventListener('dblclick', () => {
            if (!node.directory) {
                openFile(node.path);
            }
        });
        row.addEventListener('contextmenu', (event) => {
            event.preventDefault();
            model.selectedPath = node.path;
            model.selectedDirectory = node.directory;
            renderFiles();
            showContextMenu(event.clientX, event.clientY, node.path, node.directory);
        });
        row.addEventListener('keydown', (event) => handleTreeKey(event, node));
        item.append(row);

        if (node.directory) {
            const children = document.createElement('ul');
            children.className = 'tree-list';
            children.hidden = !expanded;
            children.setAttribute('role', 'group');
            visibleChildren.forEach((child) => {
                const rendered = renderTreeNode(child);
                if (rendered) {
                    children.append(rendered);
                }
            });
            item.append(children);
        }

        return item;
    }

    function sortedChildren(node) {
        return Array.from(node.children.values()).sort((left, right) => {
            if (left.directory !== right.directory) {
                return left.directory ? -1 : 1;
            }
            return left.name.localeCompare(right.name, model.locale);
        });
    }

    function treeNodeMatchesFilter(node) {
        const query = model.workspaceFilter.trim().toLocaleLowerCase(model.locale);
        if (!query || node.name.toLocaleLowerCase(model.locale).includes(query)) {
            return true;
        }
        return node.directory && sortedChildren(node).some(treeNodeMatchesFilter);
    }

    function selectTreeNode(node) {
        model.selectedPath = node.path;
        model.selectedDirectory = node.directory;
        if (node.directory) {
            if (model.expandedDirectories.has(node.path)) {
                model.expandedDirectories.delete(node.path);
            } else {
                model.expandedDirectories.add(node.path);
            }
        }
        renderFiles();
    }

    function handleTreeKey(event, node) {
        if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
            event.preventDefault();
            const bounds = event.currentTarget.getBoundingClientRect();
            showContextMenu(bounds.left + 18, bounds.bottom, node.path, node.directory);
            return;
        }
        if ((event.key === 'Enter' || event.key === ' ') && !node.directory) {
            event.preventDefault();
            openFile(node.path);
            return;
        }
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp'
                || event.key === 'Home' || event.key === 'End') {
            event.preventDefault();
            focusVisibleTreeRow(event.currentTarget, event.key);
            return;
        }
        if (event.key === 'ArrowRight' && node.directory) {
            event.preventDefault();
            if (model.expandedDirectories.has(node.path)) {
                event.currentTarget.closest('li')?.querySelector('.tree-list .tree-row')?.focus();
                return;
            }
            model.expandedDirectories.add(node.path);
            renderFiles();
            requestAnimationFrame(() => focusTreePath(node.path));
            return;
        }
        if (event.key === 'ArrowLeft') {
            event.preventDefault();
            if (node.directory && model.expandedDirectories.has(node.path)) {
                model.expandedDirectories.delete(node.path);
                renderFiles();
                requestAnimationFrame(() => focusTreePath(node.path));
                return;
            }
            focusTreePath(parentPath(node.path));
        }
    }

    function visibleTreeRows() {
        return Array.from(dom('file-tree').querySelectorAll('.tree-row'))
            .filter((row) => !row.closest('ul[hidden]'));
    }

    function focusVisibleTreeRow(current, key) {
        const rows = visibleTreeRows();
        const index = rows.indexOf(current);
        const target = key === 'Home' ? rows[0]
            : key === 'End' ? rows.at(-1)
                : rows[Math.max(0, Math.min(rows.length - 1,
                    index + (key === 'ArrowDown' ? 1 : -1)))];
        target?.focus();
    }

    function focusTreePath(path) {
        const row = Array.from(dom('file-tree').querySelectorAll('.tree-row'))
            .find((candidate) => candidate.dataset.path === path);
        row?.focus();
    }

    function parentPath(path) {
        if (!path || path === '/workspace') {
            return '/workspace';
        }
        return path.slice(0, path.lastIndexOf('/')) || '/workspace';
    }

    function fileKind(node) {
        if (node.directory) {
            return 'folder';
        }
        const extension = node.name.includes('.') ? node.name.split('.').pop().toLowerCase() : '';
        if (['html', 'htm'].includes(extension)) return 'html';
        if (['css', 'scss', 'sass', 'less'].includes(extension)) return 'css';
        if (['js', 'mjs', 'cjs', 'jsx'].includes(extension)) return 'javascript';
        if (['ts', 'tsx'].includes(extension)) return 'typescript';
        if (['md', 'mdx'].includes(extension)) return 'markdown';
        if (['json', 'jsonl'].includes(extension)) return 'json';
        if (['yml', 'yaml', 'toml'].includes(extension)) return 'yaml';
        if (['sh', 'zsh', 'bash', 'fish'].includes(extension)) return 'shell';
        if (['java', 'kt', 'kts'].includes(extension)) return 'java';
        if (['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'ico'].includes(extension)) return 'image';
        if (extension === 'pdf') return 'pdf';
        if (['zip', 'tar', 'gz', 'tgz', '7z'].includes(extension)) return 'archive';
        return 'text';
    }

    function fileIcon(node) {
        if (node.directory) {
            return model.workspaceFilter.trim() || model.expandedDirectories.has(node.path)
                ? 'bi-folder2-open' : 'bi-folder2';
        }
        return ({
            html: 'bi-filetype-html', css: 'bi-filetype-css',
            javascript: 'bi-filetype-js', typescript: 'bi-filetype-tsx',
            markdown: 'bi-filetype-md', json: 'bi-filetype-json',
            yaml: 'bi-filetype-yml', shell: 'bi-terminal',
            java: 'bi-filetype-java', image: 'bi-file-earmark-image',
            pdf: 'bi-filetype-pdf', archive: 'bi-file-earmark-zip'
        })[fileKind(node)] || 'bi-file-earmark-text';
    }

    function formatBytes(value) {
        const bytes = Number(value);
        if (!Number.isFinite(bytes) || bytes < 0) {
            return '';
        }
        if (bytes < 1024) return `${bytes} B`;
        if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`;
        return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    }

    function renderWorkspaceBreadcrumb() {
        const container = dom('workspace-breadcrumb');
        container.replaceChildren();
        const path = model.selectedPath || '/workspace';
        const parts = path.replace(/^\//, '').split('/');
        let current = '';
        parts.forEach((part, index) => {
            current += `/${part}`;
            const target = current;
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = index === 0 ? t('workspace.root') : part;
            button.title = target;
            button.addEventListener('click', () => {
                const node = model.workspaceNodes.get(target);
                if (node) selectTreeNode(node);
            });
            if (index > 0) {
                const separator = document.createElement('i');
                separator.className = 'bi bi-chevron-right';
                container.append(separator);
            }
            container.append(button);
        });
    }

    function renderWorkspaceSelection() {
        const panel = dom('workspace-selection');
        const node = model.workspaceNodes.get(model.selectedPath);
        if (!node || node.path === '/workspace') {
            panel.classList.add('d-none');
            return;
        }
        panel.classList.remove('d-none');
        dom('workspace-selection-name').textContent = node.name;
        dom('workspace-selection-path').textContent = node.path;
        dom('workspace-selection-icon').innerHTML = `<i class="bi ${fileIcon(node)}"></i>`;
        const kind = fileKind(node);
        dom('workspace-selection-icon').style.setProperty('--selection-file-color',
            getComputedStyle(document.querySelector(`.tree-row[data-path="${CSS.escape(node.path)}"]`) || document.body)
                .getPropertyValue('--file-color') || 'var(--bs-primary)');
        dom('workspace-selection-meta').textContent = node.directory
            ? t('workspace.folderMeta', {count: node.children.size})
            : t('workspace.fileMeta', {type: kind.toUpperCase(), size: formatBytes(node.size) || '—'});
        dom('workspace-selection-preview').classList.toggle('d-none', !isHtml(node.path));
    }

    function renderArtifacts() {
        const container = dom('artifact-list');
        dom('artifact-count').textContent = String(model.artifacts.length);
        container.innerHTML = '';

        if (model.artifacts.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'artifact-empty';
            empty.textContent = '—';
            container.append(empty);
            return;
        }

        model.artifacts.forEach((artifact) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'artifact-row';
            button.innerHTML = `
                <i class="bi bi-box-seam"></i>
                <span class="min-w-0"><strong class="d-block text-truncate">${escapeHtml(artifact.id)}</strong><small class="text-body-secondary d-block text-truncate">${escapeHtml(artifact.entrypoint)}</small></span>
                <i class="bi bi-eye text-body-secondary"></i>`;
            button.addEventListener('click', () => previewArtifact(artifact));
            container.append(button);
        });
    }

    function showContextMenu(x, y, path, directory) {
        const menu = dom('file-context-menu');
        const root = path === '/workspace';
        model.selectedPath = path;
        menu.querySelectorAll('[data-file-action]').forEach((element) => {
            const hidden = (element.hasAttribute('data-file-only') && directory)
                || (element.hasAttribute('data-directory-only') && !directory)
                || (element.hasAttribute('data-root-hidden') && root);
            element.classList.toggle('d-none', hidden);
        });
        const previewButton = menu.querySelector('[data-file-action="preview"]');
        previewButton.disabled = !isHtml(path);
        previewButton.classList.toggle('disabled', !isHtml(path));
        model.selectedDirectory = directory;
        menu.classList.add('show');

        const bounds = menu.getBoundingClientRect();
        menu.style.left = `${Math.max(8, Math.min(x, window.innerWidth - bounds.width - 8))}px`;
        menu.style.top = `${Math.max(8, Math.min(y, window.innerHeight - bounds.height - 8))}px`;
    }

    function hideContextMenu() {
        dom('file-context-menu').classList.remove('show');
    }

    async function openFile(path) {
        hideContextMenu();
        if (isHtml(path)) {
            previewWorkspaceFile(path);
            return;
        }

        const response = await fetch(fileContentUrl(path));
        if (!response.ok) {
            showToast(t('file.readFailed'), 'danger');
            return;
        }
        model.activeEditorPath = path;
        dom('editor-title').textContent = path.split('/').pop();
        dom('editor-path').textContent = path;
        dom('file-editor').value = await response.text();
        editorModal.show();
    }

    function fileContentUrl(path) {
        return `/api/chat/sessions/${encodeURIComponent(model.sessionId)}/files/content?path=${encodeURIComponent(path)}`;
    }

    function isHtml(path) {
        return /\.html?$/i.test(path || '');
    }

    function previewWorkspaceFile(path) {
        if (!isHtml(path)) {
            showToast(t('preview.onlyHtml'), 'warning');
            return;
        }
        const relative = path.replace(/^\/workspace\/?/, '');
        const url = `/api/chat/sessions/${encodeURIComponent(model.sessionId)}/preview/${encodePath(relative)}`;
        setPreview(path.split('/').pop(), path, url);
    }

    async function downloadFile(path) {
        const response = await fetch(fileContentUrl(path));
        if (!response.ok) {
            showToast(t('file.readFailed'), 'danger');
            return;
        }
        const blob = await response.blob();
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = path.split('/').pop() || 'workspace-file';
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    }

    function previewArtifact(artifact) {
        const root = (artifact.root || '/').replace(/\/$/, '') + '/';
        const relative = artifact.entrypoint.startsWith(root)
            ? artifact.entrypoint.slice(root.length)
            : artifact.entrypoint.split('/').pop();
        const url = `/api/chat/sessions/${encodeURIComponent(model.sessionId)}/artifacts/${encodeURIComponent(artifact.id)}/files/${encodePath(relative)}`;
        setPreview(artifact.entrypoint.split('/').pop(), `${artifact.id} · ${artifact.entrypoint}`, url);
    }

    function setPreview(name, path, url) {
        model.previewUrl = url;
        dom('preview-name').removeAttribute('data-i18n');
        dom('preview-path').removeAttribute('data-i18n');
        dom('preview-name').textContent = name;
        dom('preview-path').textContent = path;
        dom('preview-empty').classList.add('d-none');
        dom('preview-frame').classList.remove('d-none');
        dom('preview-frame').src = url;
        showPanel('preview');
    }

    function encodePath(path) {
        return path.split('/').map(encodeURIComponent).join('/');
    }

    function showPanel(name) {
        ['files', 'preview'].forEach((panel) => {
            dom(`panel-${panel}`).classList.toggle('d-none', panel !== name);
        });
        document.querySelectorAll('[data-panel]').forEach((button) => {
            button.classList.toggle('active', button.dataset.panel === name);
        });
        application.classList.remove('resources-collapsed');
    }

    async function saveFile() {
        if (!model.activeEditorPath) {
            return;
        }
        const response = await fetch(fileContentUrl(model.activeEditorPath), {
            method: 'PUT',
            headers: {'Content-Type': 'text/plain'},
            body: dom('file-editor').value
        });
        showToast(t(response.ok ? 'file.saved' : 'file.saveFailed'), response.ok ? 'success' : 'danger');
        if (response.ok) {
            editorModal.hide();
            await refreshSession();
        }
    }

    async function createFile(parent = null) {
        const base = parent || (model.selectedDirectory ? model.selectedPath : '/workspace');
        const path = prompt(t('file.newPrompt'), `${base}/untitled.md`);
        if (!path) {
            return;
        }
        const response = await fetch(fileContentUrl(path), {
            method: 'PUT',
            headers: {'Content-Type': 'text/plain'},
            body: ''
        });
        showToast(t(response.ok ? 'file.created' : 'file.createFailed'), response.ok ? 'success' : 'danger');
        if (response.ok) {
            await refreshSession();
            await openFile(path);
        }
    }

    async function createDirectory(parent = null) {
        const base = parent || (model.selectedDirectory ? model.selectedPath : '/workspace');
        const path = prompt(t('directory.newPrompt'), `${base}/new-folder`);
        if (!path) {
            return;
        }
        const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}/directories?path=${encodeURIComponent(path)}`, {method: 'POST'});
        showToast(t(response.ok ? 'directory.created' : 'directory.createFailed'), response.ok ? 'success' : 'danger');
        if (response.ok) {
            model.expandedDirectories.add(base);
            await refreshSession();
        }
    }

    async function moveFile() {
        const source = model.selectedPath;
        const target = prompt(t('file.renamePrompt'), source);
        if (!target || target === source) {
            return;
        }
        const kind = model.selectedDirectory ? 'directories' : 'files';
        const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}/${kind}/move?source=${encodeURIComponent(source)}&target=${encodeURIComponent(target)}`, {method: 'POST'});
        showToast(t(response.ok ? 'file.moved' : 'file.moveFailed'), response.ok ? 'success' : 'danger');
        if (response.ok) {
            model.selectedPath = target;
            await refreshSession();
        }
    }

    async function duplicateFile() {
        const source = model.selectedPath;
        const suggested = source.replace(/(\.[^/.]+)?$/, '-copy$1');
        const target = prompt(t('file.duplicatePrompt'), suggested);
        if (!target) {
            return;
        }
        const kind = model.selectedDirectory ? 'directories' : 'files';
        const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}/${kind}/copy?source=${encodeURIComponent(source)}&target=${encodeURIComponent(target)}`, {method: 'POST'});
        showToast(t(response.ok ? 'file.duplicated' : 'file.duplicateFailed'), response.ok ? 'success' : 'danger');
        if (response.ok) {
            await refreshSession();
        }
    }

    async function deleteFile() {
        const path = model.selectedPath;
        if (!path || !confirm(t('file.deleteConfirm', {path}))) {
            return;
        }
        const endpoint = model.selectedDirectory
            ? `/directories?path=${encodeURIComponent(path)}&recursive=true`
            : `/files?path=${encodeURIComponent(path)}`;
        const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}${endpoint}`, {method: 'DELETE'});
        showToast(t(response.ok ? 'file.deleted' : 'file.deleteFailed'), response.ok ? 'success' : 'danger');
        if (response.ok) {
            model.selectedPath = null;
            await refreshSession();
        }
    }

    async function mergeDirectory() {
        const source = model.selectedPath;
        const target = prompt(t('directory.mergePrompt'), '/workspace');
        if (!target || target === source) {
            return;
        }
        const response = await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}/directories/copy?source=${encodeURIComponent(source)}&target=${encodeURIComponent(target)}&merge=true`, {method: 'POST'});
        showToast(t(response.ok ? 'file.duplicated' : 'file.duplicateFailed'), response.ok ? 'success' : 'danger');
        if (response.ok) {
            await refreshSession();
        }
    }

    async function copySelectedPath() {
        try {
            await navigator.clipboard.writeText(model.selectedPath);
            showToast(t('file.pathCopied'), 'success');
        } catch (error) {
            showToast(t('file.copyPathFailed'), 'danger');
        }
    }

    function handleFileAction(action) {
        hideContextMenu();
        if (!model.selectedPath) {
            return;
        }
        ({
            open: () => openFile(model.selectedPath),
            preview: () => previewWorkspaceFile(model.selectedPath),
            download: () => downloadFile(model.selectedPath),
            newFile: () => createFile(model.selectedPath),
            newDirectory: () => createDirectory(model.selectedPath),
            refresh: () => refreshSession().catch(() => showToast(t('common.failed'), 'danger')),
            rename: moveFile,
            duplicate: duplicateFile,
            merge: mergeDirectory,
            copyPath: copySelectedPath,
            delete: deleteFile
        })[action]?.();
    }

    function parseSseBlock(raw, assistantBody, runActivity, streamState) {
        const eventLine = raw.split('\n').find((line) => line.startsWith('event:'));
        if (!eventLine) {
            return;
        }
        const payload = JSON.parse(raw.split('\n')
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trimStart())
            .join('\n'));
        const eventName = eventLine.slice(6).trim();

        if (eventName === 'session') {
            model.sessionId = payload.sessionId;
            localStorage.setItem('avr.session', model.sessionId);
            return;
        }
        if (eventName === 'trace') {
            if (payload.type.startsWith('tool.')) {
                if (!updatePlanPanel(payload)) {
                    updateToolActivity(runActivity, payload);
                }
                updateWorkspaceActivity(payload);
                scheduleWorkspaceRefresh(payload);
            }
            if (payload.type === 'model.call') {
                streamState.rounds.push({text: '', reasoning: '', toolCallCount: null});
                streamState.phase = 'thinking';
            } else if (payload.type === 'model.completed') {
                currentModelRound(streamState).toolCallCount =
                    Number(payload.attributes?.toolCallCount || 0);
                if (currentModelRound(streamState).toolCallCount > 0) {
                    streamState.phase = 'usingTools';
                }
            } else if (payload.type === 'tool.started') {
                streamState.phase = 'usingTools';
            }
            if (payload.type === 'model.call' || payload.type === 'model.completed'
                    || payload.type === 'tool.started') {
                renderStreamReply(assistantBody, streamState);
            }
            updateRuntimeForEvent(payload.type);
            return;
        }
        if (eventName === 'delta') {
            currentModelRound(streamState).text += payload.content;
            streamState.phase = splitThinking(currentModelRound(streamState).text).inThink
                ? 'thinking' : 'writing';
            renderStreamReply(assistantBody, streamState);
            scrollMessages();
            return;
        }
        if (eventName === 'reasoning') {
            currentModelRound(streamState).reasoning += payload.content;
            streamState.phase = 'thinking';
            renderStreamReply(assistantBody, streamState);
            scrollMessages();
            return;
        }
        if (eventName === 'done') {
            streamState.done = true;
            if (!currentModelRound(streamState).text) {
                currentModelRound(streamState).text = payload.content || '';
            }
            renderStreamReply(assistantBody, streamState);
            assistantBody.innerHTML = markdown(visibleText(payload.content) || t('message.empty'));
            runActivity.activityModel.status = 'done';
            runActivity.activityModel.steps = payload.steps;
            renderRunActivity(runActivity);
            model.files = payload.files || [];
            model.artifacts = payload.artifacts || [];
            renderFiles();
            renderArtifacts();
            refreshWorkspace().catch(() => undefined);
            return;
        }
        if (eventName === 'error') {
            throw new Error(payload.content);
        }
    }

    async function consumeSseResponse(response, assistantBody, runActivity, streamState) {
        if (!response.ok || !response.body) {
            throw new Error(`HTTP ${response.status}`);
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let pending = '';
        while (true) {
            const result = await reader.read();
            pending += decoder.decode(result.value || new Uint8Array(), {
                stream: !result.done
            }).replace(/\r\n/g, '\n');

            let separator;
            while ((separator = pending.indexOf('\n\n')) >= 0) {
                const block = pending.slice(0, separator);
                pending = pending.slice(separator + 2);
                parseSseBlock(block, assistantBody, runActivity, streamState);
            }
            if (result.done) {
                if (pending.trim()) {
                    parseSseBlock(pending, assistantBody, runActivity, streamState);
                }
                break;
            }
        }
        if (!streamState.done) {
            throw new Error('SSE stream ended unexpectedly');
        }
    }

    async function resumeRun(runId, after, assistantBody, runActivity, streamState) {
        const waitingTimer = setInterval(() => updateReplyElapsed(assistantBody), 1000);
        try {
            const url = `/api/chat/sessions/${encodeURIComponent(model.sessionId)}`
                + `/runs/${encodeURIComponent(runId)}/events?after=${after}`;
            await consumeSseResponse(
                await fetch(url), assistantBody, runActivity, streamState);
            setRuntimeState('completed');
            await refreshWorkspace();
        } catch (error) {
            renderRunFailure(assistantBody, error.message);
            runActivity.activityModel.status = 'failed';
            renderRunActivity(runActivity);
            setRuntimeState('failed');
        } finally {
            clearInterval(waitingTimer);
            setComposerBusy(false);
            input.focus();
        }
    }

    function updateRuntimeForEvent(type) {
        if (type === 'run.failed') {
            setRuntimeState('failed');
        } else if (type === 'run.completed') {
            setRuntimeState('completed');
        } else {
            setRuntimeState('running');
        }
    }

    function setRuntimeState(state) {
        model.runtimeState = state;
        const runtime = dom('runtime-state');
        runtime.classList.toggle('is-running', state === 'running');
        runtime.classList.toggle('is-failed', state === 'failed');
        const key = state === 'running'
            ? 'runtime.running'
            : state === 'completed'
                ? 'runtime.completed'
                : state === 'failed'
                    ? 'runtime.failed'
                    : 'runtime.ready';
        runtime.querySelector('span:last-child').textContent = t(key);
        dom('composer-status').textContent = t(key);
    }

    async function submit() {
        const text = input.value.trim();
        if (!text || model.busy) {
            return;
        }

        setComposerBusy(true);
        input.value = '';
        resizeComposer();
        setRuntimeState('running');
        resetPlanPanel();

        createTurn('user', text);
        const assistant = createTurn('assistant', '…');
        const runActivity = createRunActivity(assistant.contentColumn);
        const streamState = {
            rounds: [], done: false,
            phase: 'waiting', startedAt: Date.now()
        };
        renderStreamReply(assistant.body, streamState);
        const waitingTimer = setInterval(() => updateReplyElapsed(assistant.body), 1000);

        try {
            const response = await fetch('/api/chat/stream', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    sessionId: model.sessionId,
                    message: text,
                    model: model.selectedModel,
                    webSearch: webSearchEnabledForSelection()
                })
            });
            await consumeSseResponse(response, assistant.body, runActivity, streamState);
            setRuntimeState('completed');
            await refreshWorkspace();
        } catch (error) {
            renderRunFailure(assistant.body, error.message);
            runActivity.activityModel.status = 'failed';
            renderRunActivity(runActivity);
            setRuntimeState('failed');
        } finally {
            clearInterval(waitingTimer);
            setComposerBusy(false);
            input.focus();
        }
    }

    function runErrorMessage(code) {
        const httpStatus = /^model-http:(\d{3})$/.exec(code || '');
        if (httpStatus) {
            return t('error.modelHttp', {status: httpStatus[1]});
        }
        const known = new Set([
            'timeout', 'step-limit', 'no-progress', 'model-truncated',
            'model-stream-invalid', 'model-network', 'completion-unverified', 'unknown'
        ]);
        return known.has(code) ? t(`error.${code}`) : (code || t('error.unknown'));
    }

    function renderRunFailure(body, code) {
        if (body.querySelector('.reply-loading')) {
            body.replaceChildren();
        }
        body.querySelector('.reply-progress')?.remove();
        const reasoning = body.parentElement.querySelector('.reasoning-panel');
        if (reasoning) {
            reasoning.open = false;
        }
        body.querySelector('.run-error')?.remove();
        const error = document.createElement('p');
        error.className = 'run-error';
        error.setAttribute('role', 'alert');
        error.dataset.runError = code || 'unknown';
        error.textContent = runErrorMessage(code);
        body.append(error);
    }

    async function startNewTask() {
        if (model.sessionId && !confirm(t('session.newConfirm'))) {
            return;
        }
        if (model.sessionId) {
            await fetch(`/api/chat/sessions/${encodeURIComponent(model.sessionId)}`, {
                method: 'DELETE'
            });
        }
        localStorage.removeItem('avr.session');
        location.reload();
    }

    function showToast(message, type = 'secondary') {
        const element = document.createElement('div');
        element.className = `toast align-items-center text-bg-${type} border-0`;
        element.setAttribute('role', 'status');
        element.innerHTML = `<div class="d-flex"><div class="toast-body">${escapeHtml(message)}</div><button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button></div>`;
        dom('toast-container').append(element);
        const toast = new bootstrap.Toast(element, {delay: 2200});
        element.addEventListener('hidden.bs.toast', () => element.remove());
        toast.show();
    }

    function bindEvents() {
        dom('workspace-search-shortcut').textContent = navigator.platform.toLowerCase().includes('mac')
            ? '⌘K' : 'Ctrl K';
        dom('send').addEventListener('click', submit);
        input.addEventListener('keydown', (event) => {
            if (event.isComposing || event.keyCode === 229 || event.key !== 'Enter') {
                return;
            }
            const explicitSubmit = event.ctrlKey || event.metaKey;
            const desktopSubmit = !event.shiftKey
                && !window.matchMedia('(pointer: coarse)').matches;
            if (explicitSubmit || desktopSubmit) {
                event.preventDefault();
                submit();
            }
        });
        input.addEventListener('input', () => {
            resizeComposer();
            updateSendState();
        });

        document.querySelectorAll('.suggestion').forEach((button) => {
            button.addEventListener('click', () => {
                input.value = button.textContent.trim();
                resizeComposer();
                updateSendState();
                submit();
            });
        });
        document.querySelectorAll('[data-panel]').forEach((button) => {
            button.addEventListener('click', () => showPanel(button.dataset.panel));
        });
        dom('workspace-search').addEventListener('input', (event) => {
            model.workspaceFilter = event.target.value;
            renderFiles();
        });
        dom('workspace-search').addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                event.currentTarget.value = '';
                model.workspaceFilter = '';
                renderFiles();
                event.currentTarget.blur();
            }
        });
        document.addEventListener('keydown', (event) => {
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
                event.preventDefault();
                showPanel('files');
                dom('workspace-search').focus();
            }
        });
        document.querySelectorAll('.locale-option').forEach((button) => {
            button.addEventListener('click', async () => {
                model.locale = button.dataset.locale;
                localStorage.setItem('avr.locale', model.locale);
                await loadTranslations();
                applyI18n();
                syncWebSearchControl();
            });
        });
        document.querySelectorAll('.theme-option').forEach((button) => {
            button.addEventListener('click', () => applyTheme(button.dataset.theme));
        });
        matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
            if (model.theme === 'system') {
                applyTheme('system');
            }
        });

        dom('toggle-resources').addEventListener('click', () => application.classList.toggle('resources-collapsed'));
        dom('collapse-resources').addEventListener('click', () => application.classList.add('resources-collapsed'));
        dom('workspace-refresh').addEventListener('click', () =>
            refreshWorkspace().catch(() => showToast(t('common.failed'), 'danger')));
        dom('collapse-tree').addEventListener('click', () => {
            model.expandedDirectories = new Set(['/workspace']);
            renderFiles();
        });
        dom('workspace-menu').addEventListener('click', (event) => {
            const bounds = event.currentTarget.getBoundingClientRect();
            showContextMenu(bounds.right, bounds.bottom, '/workspace', true);
        });
        dom('copy-workspace-path').addEventListener('click', () => {
            if (!model.selectedPath) model.selectedPath = '/workspace';
            copySelectedPath();
        });
        dom('workspace-selection-preview').addEventListener('click', () => {
            if (model.selectedPath) previewWorkspaceFile(model.selectedPath);
        });
        dom('artifact-toggle').addEventListener('click', (event) => {
            const button = event.currentTarget;
            const expanded = button.getAttribute('aria-expanded') !== 'true';
            button.setAttribute('aria-expanded', String(expanded));
            dom('artifact-list').classList.toggle('d-none', !expanded);
        });
        dom('nav-workspace').addEventListener('click', () => showPanel('files'));
        dom('toggle-navigation').addEventListener('click', () => dom('navigation').classList.toggle('is-open'));
        dom('new-task').addEventListener('click', startNewTask);
        dom('file-tree').addEventListener('contextmenu', (event) => {
            if (event.target.closest('.tree-row')) {
                return;
            }
            event.preventDefault();
            showContextMenu(event.clientX, event.clientY, '/workspace', true);
        });
        dom('save-file').addEventListener('click', saveFile);
        dom('model-select').addEventListener('change', (event) => {
            model.selectedModel = event.target.value;
            localStorage.setItem('avr.model', model.selectedModel);
            syncWebSearchControl();
        });
        dom('web-search-toggle').addEventListener('change', (event) => {
            model.webSearch = event.target.checked;
            model.webSearchSaved = true;
            localStorage.setItem('avr.webSearch', String(model.webSearch));
        });
        dom('reload-preview').addEventListener('click', () => {
            if (model.previewUrl) {
                dom('preview-frame').src = model.previewUrl;
            }
        });
        dom('open-preview').addEventListener('click', () => {
            if (model.previewUrl) {
                window.open(model.previewUrl, '_blank', 'noopener,noreferrer');
            }
        });

        document.querySelectorAll('[data-file-action]').forEach((button) => {
            button.addEventListener('click', () => handleFileAction(button.dataset.fileAction));
        });
        document.addEventListener('click', (event) => {
            if (!dom('file-context-menu').contains(event.target)) {
                hideContextMenu();
            }
        });
        window.addEventListener('resize', hideContextMenu);
    }

    async function initialize() {
        await loadTranslations();
        applyI18n();
        applyTheme(model.theme);
        if (matchMedia('(max-width: 700px)').matches) {
            application.classList.add('resources-collapsed');
        }
        bindEvents();
        const modelsResponse = await fetch('/api/chat/models');
        if (modelsResponse.ok) {
            const catalog = await modelsResponse.json();
            const select = dom('model-select');
            model.modelCatalog = (catalog.models || []).map((entry) =>
                typeof entry === 'string'
                    ? {id: entry, name: entry, webSearch: 'none', webSearchSupported: false}
                    : entry);
            model.modelCatalog.forEach((entry) =>
                select.add(new Option(entry.name || entry.id, entry.id)));
            model.selectedModel = model.modelCatalog.some(
                (entry) => entry.id === model.selectedModel)
                ? model.selectedModel : catalog.defaultModel;
            select.value = model.selectedModel;
            if (!model.webSearchSaved) {
                model.webSearch = catalog.defaultWebSearch !== false;
            }
        } else {
            const select = dom('model-select');
            select.add(new Option('Default', ''));
            model.selectedModel = '';
            model.modelCatalog = [];
        }
        syncWebSearchControl();
        resizeComposer();
        updateSendState();

        try {
            await ensureSession();
            setRuntimeState(model.busy ? 'running' : 'ready');
        } catch (error) {
            setRuntimeState('failed');
            showToast(t('common.failed'), 'danger');
        }
    }

    initialize();
})();
