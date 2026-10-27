(() => {
  'use strict';

  const SVG_NS = 'http://www.w3.org/2000/svg';
  const LINE_HEIGHT = 22;
  const EDITOR_PAD = 14;
  const MAX_CODE = 10000;
  const GAP_X = 28;

  const FALLBACK_CODE = `public class Hello {
    public static void main(String[] args) {
        for (int i = 1; i <= 3; i++) {
            System.out.println("Step " + i);
        }
    }
}`;

  const DIMS = {
    start: [140, 46],
    end: [140, 46],
    decision: [192, 100],
    io: [204, 56],
    call: [204, 56],
    process: [204, 56],
    merge: [14, 14],
  };

  const els = {};
  const state = {
    samples: [],
    methods: [],
    trace: [],
    stepIndex: 0,
    selectedMethod: null,
    activeLine: 0,
    errorLines: new Set(),
    flow: null,
    zoom: 1,
    playing: false,
    timer: null,
    analyzing: false,
    dirty: false,
    fileName: null,
  };

  document.addEventListener('DOMContentLoaded', init);

  function init() {
    [
      'editor', 'gutter', 'lineHighlight', 'fileName', 'charCount', 'banner',
      'analyzeBtn', 'analyzeLabel', 'spinner', 'sampleSelect', 'uploadBtn', 'fileInput',
      'caption', 'stepBadge', 'prevBtn', 'playBtn', 'nextBtn', 'stepSlider',
      'speedSelect', 'methodSelect', 'flowSvg', 'flowScroll', 'flowEmpty', 'traceFlag',
      'variables', 'varCount', 'callStack', 'console', 'zoomIn', 'zoomOut', 'zoomFit', 'toast',
    ].forEach((id) => {
      els[id] = document.getElementById(id);
    });

    els.editor.placeholder = 'Paste Java code here, or pick a sample from the top right…';
    els.editor.value = FALLBACK_CODE;
    refreshEditor();
    setActiveLine(0);
    clearLowerPanels();
    updateControls();

    els.editor.addEventListener('input', onEditorInput);
    els.editor.addEventListener('scroll', syncScroll);
    els.editor.addEventListener('keydown', onEditorKeyDown);

    els.analyzeBtn.addEventListener('click', analyze);
    els.uploadBtn.addEventListener('click', () => els.fileInput.click());
    els.fileInput.addEventListener('change', onFileChosen);
    els.sampleSelect.addEventListener('change', () => {
      if (els.sampleSelect.value) {
        loadSample(els.sampleSelect.value, true);
      }
    });

    els.prevBtn.addEventListener('click', () => stepBy(-1));
    els.nextBtn.addEventListener('click', () => stepBy(1));
    els.playBtn.addEventListener('click', togglePlay);
    els.stepSlider.addEventListener('input', () => {
      stopPlayback();
      state.stepIndex = Number(els.stepSlider.value);
      renderStep();
    });
    els.speedSelect.addEventListener('change', () => {
      if (state.playing) {
        scheduleNextStep();
      }
    });
    els.methodSelect.addEventListener('change', () => {
      const method = state.methods.find((item) => item.signature === els.methodSelect.value);
      if (method) {
        state.selectedMethod = method;
        renderFlowchart();
      }
    });

    els.zoomIn.addEventListener('click', () => changeZoom(1.2));
    els.zoomOut.addEventListener('click', () => changeZoom(1 / 1.2));
    els.zoomFit.addEventListener('click', fitFlow);

    document.addEventListener('keydown', onGlobalKeyDown);
    window.addEventListener('resize', () => {
      if (state.flow) {
        applyZoom();
      }
    });

    bootstrap();
  }

  async function bootstrap() {
    try {
      const response = await fetch('/api/samples');
      if (response.ok) {
        state.samples = await response.json();
      }
    } catch (error) {
      // The fallback program still works once the server is reachable.
    }

    els.sampleSelect.textContent = '';
    state.samples.forEach((sample) => {
      const option = document.createElement('option');
      option.value = sample.id;
      option.textContent = sample.name;
      option.title = sample.description || '';
      els.sampleSelect.append(option);
    });

    if (state.samples.length) {
      els.sampleSelect.value = state.samples[0].id;
      loadSample(state.samples[0].id, false);
    } else {
      els.sampleSelect.classList.add('hidden');
    }
    analyze();
  }

  function loadSample(id, run) {
    const sample = state.samples.find((item) => item.id === id);
    if (!sample) {
      return;
    }
    state.fileName = null;
    els.editor.value = sample.code;
    els.editor.scrollTop = 0;
    els.editor.scrollLeft = 0;
    refreshEditor();
    setActiveLine(0);
    if (run) {
      analyze();
    }
  }

  async function onFileChosen(event) {
    const file = event.target.files && event.target.files[0];
    event.target.value = '';
    if (!file) {
      return;
    }
    try {
      const text = await file.text();
      state.fileName = file.name;
      els.editor.value = text;
      els.editor.scrollTop = 0;
      refreshEditor();
      setActiveLine(0);
      analyze();
    } catch (error) {
      showToast('Could not read that file.');
    }
  }

  // ------------------------------------------------------------------ editor

  function onEditorInput() {
    refreshEditor();
    clearBanner();
    state.errorLines.clear();
    renderGutter();
    if (state.trace.length && !state.dirty) {
      state.dirty = true;
      els.caption.textContent = 'Code changed — run it again to refresh the visualization.';
    }
  }

  function onEditorKeyDown(event) {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      analyze();
      return;
    }
    if (event.key === 'Tab') {
      event.preventDefault();
      const start = els.editor.selectionStart;
      const end = els.editor.selectionEnd;
      els.editor.setRangeText('    ', start, end, 'end');
      refreshEditor();
    }
  }

  function onGlobalKeyDown(event) {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      analyze();
      return;
    }
    const tag = (event.target.tagName || '').toLowerCase();
    if (tag === 'textarea' || tag === 'input' || tag === 'select' || event.target.isContentEditable) {
      return;
    }
    if (!state.trace.length) {
      return;
    }
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      stepBy(-1);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      stepBy(1);
    } else if (event.key === ' ') {
      event.preventDefault();
      togglePlay();
    }
  }

  function refreshEditor() {
    renderGutter();
    updateCounter();
    updateFileName();
    syncScroll();
    paintHighlight();
  }

  function updateFileName() {
    const match = els.editor.value.match(/\b(?:class|record|enum|interface)\s+([A-Za-z_$][\w$]*)/);
    const name = match ? match[1] + '.java' : (state.fileName || 'Main.java');
    els.fileName.textContent = name;
  }

  function updateCounter() {
    const length = els.editor.value.length;
    els.charCount.textContent = `${length.toLocaleString()} / 10,000`;
    els.charCount.classList.toggle('over', length > MAX_CODE);
  }

  function renderGutter() {
    const lineCount = els.editor.value.split('\n').length;
    const fragment = document.createDocumentFragment();
    for (let line = 1; line <= lineCount; line += 1) {
      const div = document.createElement('div');
      div.className = 'ln';
      if (state.errorLines.has(line)) {
        div.classList.add('error');
      }
      if (state.activeLine === line) {
        div.classList.add('active');
      }
      div.dataset.line = String(line);
      div.textContent = String(line);
      fragment.append(div);
    }
    els.gutter.textContent = '';
    els.gutter.append(fragment);
    els.gutter.scrollTop = els.editor.scrollTop;
  }

  function syncScroll() {
    els.gutter.scrollTop = els.editor.scrollTop;
    paintHighlight();
  }

  function paintHighlight() {
    if (!state.activeLine) {
      els.lineHighlight.style.opacity = '0';
      return;
    }
    const top = (state.activeLine - 1) * LINE_HEIGHT - els.editor.scrollTop;
    els.lineHighlight.style.transform = `translateY(${top}px)`;
    els.lineHighlight.style.opacity = '1';
  }

  function setActiveLine(line) {
    state.activeLine = line;
    els.gutter.querySelectorAll('.ln').forEach((item) => {
      item.classList.toggle('active', Number(item.dataset.line) === line);
    });
    paintHighlight();
  }

  function scrollEditorToLine(line, selectLine) {
    const lines = els.editor.value.split('\n');
    if (line < 1 || line > lines.length) {
      return;
    }
    if (selectLine) {
      let start = 0;
      for (let index = 0; index < line - 1; index += 1) {
        start += lines[index].length + 1;
      }
      els.editor.focus();
      els.editor.setSelectionRange(start, start + lines[line - 1].length);
    }
    const target = (line - 1) * LINE_HEIGHT;
    const viewHeight = els.editor.clientHeight - EDITOR_PAD * 2;
    if (target < els.editor.scrollTop + 6 || target + LINE_HEIGHT > els.editor.scrollTop + viewHeight - 6) {
      els.editor.scrollTop = Math.max(0, target - viewHeight / 2 + LINE_HEIGHT / 2);
    }
    syncScroll();
  }

  // ------------------------------------------------------------------ analyze

  async function analyze() {
    if (state.analyzing) {
      return;
    }
    const code = els.editor.value;
    if (!code.trim()) {
      showToast('Paste or upload some Java code first.');
      els.editor.focus();
      return;
    }

    state.analyzing = true;
    stopPlayback();
    setBusy(true);
    clearBanner();
    try {
      const response = await fetch('/api/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code }),
      });
      if (!response.ok) {
        throw new Error(`Server responded with ${response.status}`);
      }
      const data = await response.json();
      if (els.editor.value !== code) {
        showToast('Code changed while it was running — press Run again.');
        return;
      }
      applyResponse(data);
    } catch (error) {
      clearStage();
      showToast('Could not analyze the code. Is the server running?');
    } finally {
      state.analyzing = false;
      setBusy(false);
    }
  }

  function setBusy(busy) {
    els.analyzeBtn.disabled = busy;
    els.sampleSelect.disabled = busy;
    els.uploadBtn.disabled = busy;
    els.spinner.classList.toggle('hidden', !busy);
    els.analyzeLabel.textContent = busy ? 'Running…' : 'Run & visualize';
  }

  function applyResponse(data) {
    if (!data.valid) {
      clearStage();
      const errors = Array.isArray(data.errors) ? data.errors : [];
      state.errorLines = new Set(errors.map((error) => error.line).filter((line) => line > 0));
      renderGutter();
      showErrors(errors, data.warnings || []);
      els.caption.textContent = 'Fix the errors in the editor, then run again.';
      return;
    }

    state.errorLines.clear();
    renderGutter();
    state.methods = Array.isArray(data.methods) ? data.methods : [];
    state.trace = Array.isArray(data.trace) ? data.trace : [];
    state.stepIndex = 0;
    state.dirty = false;
    setActiveLine(0);

    if (data.warnings && data.warnings.length) {
      showWarnings(data.warnings);
    } else {
      clearBanner();
    }

    buildMethodSelect();
    chooseDefaultMethod();

    if (state.methods.length && state.selectedMethod) {
      renderFlowchart();
    } else {
      state.flow = null;
      els.flowSvg.textContent = '';
      els.flowEmpty.classList.remove('hidden');
    }

    els.stepSlider.max = String(Math.max(0, state.trace.length - 1));
    els.stepSlider.value = '0';

    if (data.traceTruncated) {
      els.traceFlag.textContent = 'Trace truncated';
      els.traceFlag.classList.remove('hidden');
    } else if (!state.trace.length) {
      els.traceFlag.textContent = 'Flowchart only';
      els.traceFlag.classList.remove('hidden');
    } else {
      els.traceFlag.classList.add('hidden');
    }

    if (state.trace.length) {
      renderStep();
    } else {
      clearLowerPanels();
      els.stepBadge.textContent = '0 / 0';
      els.caption.textContent = 'The code is valid, but no main method was executed. The flowchart is still shown.';
      updateControls();
    }
  }

  function buildMethodSelect() {
    els.methodSelect.textContent = '';
    state.methods.forEach((method) => {
      const option = document.createElement('option');
      option.value = method.signature;
      option.textContent = method.signature;
      els.methodSelect.append(option);
    });
    els.methodSelect.disabled = state.methods.length === 0;
  }

  function chooseDefaultMethod() {
    const firstStep = state.trace[0];
    let method = null;
    if (firstStep) {
      method = state.methods.find((item) => item.name === firstStep.method) || null;
    }
    if (!method) {
      method = state.methods.find((item) => item.name === 'main') || state.methods[0] || null;
    }
    state.selectedMethod = method;
    if (method) {
      els.methodSelect.value = method.signature;
    }
  }

  function clearStage() {
    stopPlayback();
    state.methods = [];
    state.trace = [];
    state.stepIndex = 0;
    state.selectedMethod = null;
    state.flow = null;
    state.zoom = 1;
    state.errorLines = new Set();
    state.dirty = false;

    els.flowSvg.textContent = '';
    els.flowSvg.removeAttribute('viewBox');
    els.flowSvg.removeAttribute('width');
    els.flowSvg.removeAttribute('height');
    els.flowSvg.style.width = '';
    els.flowSvg.style.height = '';
    els.flowEmpty.classList.remove('hidden');
    els.traceFlag.classList.add('hidden');

    els.methodSelect.textContent = '';
    els.methodSelect.disabled = true;
    els.stepBadge.textContent = '0 / 0';
    els.stepSlider.max = '0';
    els.stepSlider.value = '0';
    els.caption.textContent = 'Run a program to see each step explained.';

    setActiveLine(0);
    clearLowerPanels();
    updateControls();
  }

  function clearLowerPanels() {
    els.variables.innerHTML = '';
    els.variables.append(mutedLine('No variables yet.'));
    els.varCount.textContent = '';
    els.callStack.innerHTML = '';
    els.callStack.append(mutedLine('—'));
    els.console.textContent = '';
    els.console.append(mutedSpan('No output yet.'));
  }

  function mutedLine(text) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = text;
    return p;
  }

  function mutedSpan(text) {
    const span = document.createElement('span');
    span.className = 'muted';
    span.textContent = text;
    return span;
  }

  // ------------------------------------------------------------------ steps

  function renderStep() {
    const step = state.trace[state.stepIndex];
    if (!step) {
      updateControls();
      return;
    }

    const method = state.methods.find((item) => item.name === step.method);
    if (method && state.selectedMethod && method.signature !== state.selectedMethod.signature) {
      state.selectedMethod = method;
      els.methodSelect.value = method.signature;
      renderFlowchart();
    }

    setActiveLine(step.line);
    scrollEditorToLine(step.line, false);
    highlightFlowNode(step.line, step.caption);

    const previous = state.stepIndex > 0 ? state.trace[state.stepIndex - 1] : null;
    renderVariables(step, previous);
    renderStack(step.stack || []);
    renderConsole(step.output || '');

    els.caption.textContent = step.caption || `Line ${step.line}`;
    els.stepBadge.textContent = `${state.stepIndex + 1} / ${state.trace.length}`;
    els.stepSlider.value = String(state.stepIndex);
    updateControls();
  }

  function stepBy(delta) {
    if (!state.trace.length) {
      return;
    }
    stopPlayback();
    const next = Math.min(Math.max(state.stepIndex + delta, 0), state.trace.length - 1);
    if (next !== state.stepIndex) {
      state.stepIndex = next;
      renderStep();
    }
  }

  function updateControls() {
    const hasTrace = state.trace.length > 0;
    els.prevBtn.disabled = !hasTrace || state.stepIndex <= 0;
    els.nextBtn.disabled = !hasTrace || state.stepIndex >= state.trace.length - 1;
    els.playBtn.disabled = !hasTrace || state.trace.length < 2;
    els.stepSlider.disabled = !hasTrace;
    els.playBtn.textContent = state.playing ? '❚❚' : '▶';
  }

  function togglePlay() {
    if (state.playing) {
      stopPlayback();
    } else {
      startPlayback();
    }
  }

  function startPlayback() {
    if (state.trace.length < 2) {
      return;
    }
    if (state.stepIndex >= state.trace.length - 1) {
      state.stepIndex = 0;
      renderStep();
    }
    state.playing = true;
    updateControls();
    scheduleNextStep();
  }

  function scheduleNextStep() {
    clearTimeout(state.timer);
    if (!state.playing) {
      return;
    }
    state.timer = setTimeout(() => {
      if (!state.playing) {
        return;
      }
      if (state.stepIndex < state.trace.length - 1) {
        state.stepIndex += 1;
        renderStep();
        scheduleNextStep();
      } else {
        stopPlayback();
      }
    }, Math.max(120, Number(els.speedSelect.value) || 900));
  }

  function stopPlayback() {
    state.playing = false;
    clearTimeout(state.timer);
    state.timer = null;
    if (els.playBtn) {
      els.playBtn.textContent = '▶';
      els.playBtn.disabled = state.trace.length < 2;
    }
  }

  // ------------------------------------------------------------------ state panels

  function renderVariables(step, previous) {
    const previousValues = new Map((previous && previous.locals ? previous.locals : [])
      .map((variable) => [variable.name, variable.value]));
    const variables = (step.locals || []).filter(
      (variable) => !(variable.name === 'args' && variable.value === '[]'));

    els.variables.textContent = '';
    els.varCount.textContent = variables.length ? String(variables.length) : '';

    if (!variables.length) {
      els.variables.append(mutedLine('No variables in scope.'));
      return;
    }

    variables.forEach((variable) => {
      const changed = previousValues.has(variable.name)
        && previousValues.get(variable.name) !== variable.value;

      const card = document.createElement('div');
      card.className = 'var-card' + (changed ? ' changed' : '');

      const name = document.createElement('div');
      name.className = 'var-name';
      const nameText = document.createElement('span');
      nameText.textContent = variable.name;
      const typeText = document.createElement('span');
      typeText.className = 'var-type';
      typeText.textContent = variable.type || '';
      typeText.title = variable.type || '';
      name.append(nameText, typeText);

      const value = document.createElement('div');
      value.className = 'var-value';

      const arrayItems = parseArrayDisplay(variable.value);
      const previousArray = previousValues.has(variable.name)
        ? parseArrayDisplay(previousValues.get(variable.name))
        : null;

      if (arrayItems) {
        const cells = document.createElement('div');
        cells.className = 'array-cells';
        arrayItems.forEach((item, index) => {
          const cell = document.createElement('span');
          const itemChanged = previousArray && previousArray[index] !== item;
          cell.className = 'cell' + (itemChanged ? ' changed' : '');
          cell.title = `[${index}] = ${item}`;
          const idx = document.createElement('span');
          idx.className = 'idx';
          idx.textContent = String(index);
          const val = document.createElement('span');
          val.className = 'val';
          val.textContent = item;
          cell.append(idx, val);
          cells.append(cell);
        });
        value.append(cells);
      } else {
        value.textContent = variable.value;
      }

      card.append(name, value);
      els.variables.append(card);
    });
  }

  function parseArrayDisplay(value) {
    if (typeof value !== 'string' || !value.startsWith('[') || !value.endsWith(']')) {
      return null;
    }
    let inner = value.slice(1, -1).trim();
    inner = inner.replace(/,\s*\.\.\.\(\d+ items\)$/, '');
    if (!inner) {
      return [];
    }
    if (inner.includes('[') || inner.includes('{')) {
      return null;
    }
    return inner.split(',').map((item) => item.trim());
  }

  function renderStack(frames) {
    els.callStack.textContent = '';
    if (!frames.length) {
      els.callStack.append(mutedLine('No frames recorded.'));
      return;
    }
    frames.forEach((frame, index) => {
      const div = document.createElement('div');
      div.className = 'frame' + (index === 0 ? ' top' : '');
      div.textContent = (index === 0 ? '▸ ' : '') + frame;
      div.title = frame;
      els.callStack.append(div);
    });
  }

  function renderConsole(output) {
    els.console.textContent = '';
    if (!output) {
      els.console.append(mutedSpan('No output yet.'));
      return;
    }
    els.console.textContent = output;
    els.console.scrollTop = els.console.scrollHeight;
  }

  // ------------------------------------------------------------------ banner

  function showErrors(errors, warnings) {
    els.banner.textContent = '';
    els.banner.className = 'banner error';

    const title = document.createElement('div');
    title.className = 'banner-title';
    title.append(document.createTextNode('Fix these issues before visualizing'));
    const count = document.createElement('span');
    count.textContent = `${errors.length} issue${errors.length === 1 ? '' : 's'}`;
    title.append(count);
    els.banner.append(title);

    const list = document.createElement('div');
    list.className = 'error-list';
    errors.slice(0, 8).forEach((error) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'error-item';
      const phase = error.phase ? `${error.phase} · ` : '';
      button.textContent = `Line ${error.line}:${error.column} — ${phase}${error.message}`;
      button.addEventListener('click', () => scrollEditorToLine(error.line, true));
      list.append(button);
    });
    if (errors.length > 8) {
      const more = document.createElement('div');
      more.className = 'muted';
      more.textContent = `+ ${errors.length - 8} more`;
      list.append(more);
    }
    els.banner.append(list);

    if (warnings && warnings.length) {
      const gap = document.createElement('div');
      gap.className = 'gap';
      els.banner.append(gap, warningList(warnings));
    }
  }

  function showWarnings(warnings) {
    els.banner.textContent = '';
    els.banner.className = 'banner warn';
    const title = document.createElement('div');
    title.className = 'banner-title';
    title.append(document.createTextNode('Heads up'));
    els.banner.append(title, warningList(warnings));
  }

  function warningList(warnings) {
    const list = document.createElement('ul');
    list.className = 'warning-list';
    warnings.forEach((warning) => {
      const item = document.createElement('li');
      item.textContent = warning;
      list.append(item);
    });
    return list;
  }

  function clearBanner() {
    els.banner.className = 'banner hidden';
    els.banner.textContent = '';
  }

  // ------------------------------------------------------------------ flowchart

  function renderFlowchart() {
    const method = state.selectedMethod;
    if (!method || !method.nodes || !method.nodes.length) {
      state.flow = null;
      els.flowSvg.textContent = '';
      els.flowEmpty.classList.remove('hidden');
      return;
    }
    const layout = layoutGraph(method);
    if (!layout) {
      return;
    }
    state.flow = layout;
    drawFlow(layout);
    els.flowEmpty.classList.add('hidden');
    fitFlow();
    if (state.activeLine) {
      highlightFlowNode(state.activeLine);
    }
  }

  function layoutGraph(method) {
    const nodes = method.nodes;
    const edges = Array.isArray(method.edges) ? method.edges : [];
    const byId = new Map(nodes.map((node) => [node.id, node]));
    const adjacency = new Map(nodes.map((node) => [node.id, []]));
    edges.forEach((edge) => {
      if (adjacency.has(edge.from) && byId.has(edge.to)) {
        adjacency.get(edge.from).push(edge.to);
      }
    });

    const start = nodes.find((node) => node.type === 'start') || nodes[0];
    const depth = new Map([[start.id, 0]]);
    const inStack = new Set();

    const visit = (id) => {
      if (inStack.has(id)) {
        return;
      }
      inStack.add(id);
      const current = depth.get(id) || 0;
      (adjacency.get(id) || []).forEach((next) => {
        if (inStack.has(next)) {
          return;
        }
        const nextDepth = current + 1;
        if (!depth.has(next) || nextDepth > depth.get(next)) {
          depth.set(next, nextDepth);
          visit(next);
        }
      });
      inStack.delete(id);
    };
    visit(start.id);

    let maxDepth = 0;
    depth.forEach((value) => {
      maxDepth = Math.max(maxDepth, value);
    });
    nodes.forEach((node) => {
      if (!depth.has(node.id)) {
        maxDepth += 1;
        depth.set(node.id, maxDepth);
      }
    });

    const layers = Array.from({ length: maxDepth + 1 }, () => []);
    nodes.forEach((node) => {
      layers[depth.get(node.id)].push(node);
    });

    const dimsOf = (node) => DIMS[node.type] || DIMS.process;
    let widest = 0;
    layers.forEach((layer) => {
      let width = 0;
      layer.forEach((node, index) => {
        width += dimsOf(node)[0] + (index > 0 ? GAP_X : 0);
      });
      widest = Math.max(widest, width);
    });

    const positions = new Map();
    let cursorY = 24;
    layers.forEach((layer) => {
      let layerWidth = 0;
      layer.forEach((node, index) => {
        layerWidth += dimsOf(node)[0] + (index > 0 ? GAP_X : 0);
      });
      const layerHeight = layer.reduce((max, node) => Math.max(max, dimsOf(node)[1]), 0);
      let cursorX = (widest - layerWidth) / 2 + 30;
      layer.forEach((node) => {
        const [w, h] = dimsOf(node);
        positions.set(node.id, {
          x: cursorX,
          y: cursorY + (layerHeight - h) / 2,
          w,
          h,
          node,
        });
        cursorX += w + GAP_X;
      });
      cursorY += layerHeight + 72;
    });

    return {
      nodes,
      edges,
      positions,
      width: widest + 60,
      height: cursorY - 72 + 28,
    };
  }

  function drawFlow(layout) {
    const svg = els.flowSvg;
    svg.textContent = '';
    svg.setAttribute('viewBox', `0 0 ${layout.width} ${layout.height}`);

    const defs = createSvg('defs');
    defs.append(
      createMarker('arrow', '#465a7c'),
      createMarker('arrow-yes', 'rgba(52, 211, 153, 0.85)'),
      createMarker('arrow-no', 'rgba(251, 113, 133, 0.8)'),
    );
    svg.append(defs);

    const edgeLayer = createSvg('g');
    const nodeLayer = createSvg('g');
    svg.append(edgeLayer, nodeLayer);

    layout.edges.forEach((edge) => {
      const from = layout.positions.get(edge.from);
      const to = layout.positions.get(edge.to);
      if (!from || !to) {
        return;
      }
      const geometry = edgeGeometry(from, to);
      const labelClass = geometry.backward
        ? 'loop'
        : edge.label === 'Yes' ? 'yes' : edge.label === 'No' ? 'no' : '';
      const markerId = geometry.backward
        ? 'arrow'
        : edge.label === 'Yes' ? 'arrow-yes' : edge.label === 'No' ? 'arrow-no' : 'arrow';

      const path = createSvg('path', {
        d: geometry.path,
        class: `flow-edge ${labelClass}`.trim(),
        'marker-end': `url(#${markerId})`,
      });
      edgeLayer.append(path);

      if (edge.label) {
        const text = createSvg('text', {
          x: geometry.labelX,
          y: geometry.labelY,
          class: `edge-label ${edge.label === 'Yes' ? 'yes' : edge.label === 'No' ? 'no' : ''}`.trim(),
          'text-anchor': 'middle',
        });
        text.textContent = edge.label;
        edgeLayer.append(text);
      }
    });

    layout.nodes.forEach((node) => {
      const geometry = layout.positions.get(node.id);
      if (!geometry) {
        return;
      }
      const group = createSvg('g', {
        class: `flow-node type-${node.type}`,
        'data-line': String(node.line || 0),
        'data-id': node.id,
      });
      const title = createSvg('title');
      title.textContent = node.label ? `${node.label} (line ${node.line})` : `line ${node.line}`;
      group.append(title);

      const { x, y, w, h } = geometry;
      const cx = x + w / 2;
      const cy = y + h / 2;

      let shape;
      if (node.type === 'decision') {
        shape = createSvg('polygon', {
          class: 'node-shape',
          points: `${cx},${y} ${x + w},${cy} ${cx},${y + h} ${x},${cy}`,
        });
      } else if (node.type === 'io') {
        shape = createSvg('polygon', {
          class: 'node-shape',
          points: `${x + 13},${y} ${x + w},${y} ${x + w - 13},${y + h} ${x},${y + h}`,
        });
      } else if (node.type === 'merge') {
        shape = createSvg('circle', { class: 'node-shape', cx, cy, r: 6 });
      } else {
        shape = createSvg('rect', {
          class: 'node-shape',
          x,
          y,
          width: w,
          height: h,
          rx: node.type === 'start' || node.type === 'end' ? h / 2 : 9,
        });
      }
      group.append(shape);

      if (node.type !== 'merge') {
        const maxChars = node.type === 'decision' ? 20 : 26;
        const maxLines = node.type === 'decision' ? 3 : 2;
        const lines = wrapText(node.label || node.type, maxChars, maxLines);
        const lineHeight = 14;
        const text = createSvg('text', {
          class: 'node-label',
          'text-anchor': 'middle',
          x: cx,
          y: cy - ((lines.length - 1) * lineHeight) / 2 + 4,
        });
        lines.forEach((line, index) => {
          const tspan = createSvg('tspan', {
            x: cx,
            dy: index === 0 ? 0 : lineHeight,
          });
          tspan.textContent = line;
          text.append(tspan);
        });
        group.append(text);
      }

      group.addEventListener('click', () => {
        if (!node.line) {
          return;
        }
        const index = state.trace.findIndex((step) => step.line === node.line);
        if (index >= 0) {
          stopPlayback();
          state.stepIndex = index;
          renderStep();
        } else {
          scrollEditorToLine(node.line, false);
        }
      });

      nodeLayer.append(group);
    });

    applyZoom();
  }

  function edgeGeometry(from, to) {
    const fromCenterX = from.x + from.w / 2;
    const fromCenterY = from.y + from.h / 2;
    const toCenterX = to.x + to.w / 2;
    const toCenterY = to.y + to.h / 2;
    const backward = to.y + to.h / 2 <= from.y + from.h / 2 + 4;

    if (!backward) {
      const x1 = fromCenterX;
      const y1 = from.y + from.h;
      const x2 = toCenterX;
      const y2 = to.y;
      return {
        path: `M ${x1} ${y1} L ${x2} ${y2}`,
        labelX: (x1 + x2) / 2,
        labelY: (y1 + y2) / 2 - 5,
        backward: false,
      };
    }

    const x1 = from.x + from.w;
    const y1 = fromCenterY;
    const x2 = to.x + to.w;
    const y2 = toCenterY;
    const bulge = Math.max(46, Math.abs(y2 - y1) * 0.4);
    const controlX = Math.max(x1, x2) + bulge;
    return {
      path: `M ${x1} ${y1} C ${controlX} ${y1}, ${controlX} ${y2}, ${x2} ${y2}`,
      labelX: controlX - 14,
      labelY: (y1 + y2) / 2 - 4,
      backward: true,
    };
  }

  function highlightFlowNode(line, caption = '') {
    const matches = [...els.flowSvg.querySelectorAll('.flow-node')]
      .filter((group) => Number(group.dataset.line) === line);
    const decisions = matches.filter((group) => group.classList.contains('type-decision'));
    const activeSet = new Set(/^checking/.test(caption) && decisions.length ? decisions : matches);
    els.flowSvg.querySelectorAll('.flow-node').forEach((group) => {
      group.classList.toggle('active', activeSet.has(group));
    });
    const firstActive = matches.find((group) => activeSet.has(group)) || null;
    if (firstActive) {
      const rect = firstActive.getBoundingClientRect();
      const box = els.flowScroll.getBoundingClientRect();
      if (rect.top < box.top + 12 || rect.bottom > box.bottom - 12) {
        els.flowScroll.scrollTop += (rect.top - box.top) - els.flowScroll.clientHeight / 2 + rect.height / 2;
      }
    }
  }

  // ------------------------------------------------------------------ svg helpers

  function createSvg(name, attributes) {
    const element = document.createElementNS(SVG_NS, name);
    if (attributes) {
      Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, value));
    }
    return element;
  }

  function createMarker(id, color) {
    const marker = createSvg('marker', {
      id,
      viewBox: '0 0 10 10',
      refX: '9',
      refY: '5',
      markerWidth: '7',
      markerHeight: '7',
      orient: 'auto-start-reverse',
      markerUnits: 'strokeWidth',
    });
    marker.append(createSvg('path', { d: 'M 0 0 L 10 5 L 0 10 z', fill: color }));
    return marker;
  }

  function wrapText(text, maxChars, maxLines) {
    const clean = String(text || '').replace(/\s+/g, ' ').trim();
    if (!clean) {
      return [''];
    }
    const words = clean.split(' ');
    const lines = [];
    let current = '';

    for (let index = 0; index < words.length; index += 1) {
      const word = words[index];
      if (!current) {
        current = word;
      } else if (`${current} ${word}`.length <= maxChars) {
        current += ` ${word}`;
      } else {
        lines.push(current);
        current = word;
        if (lines.length === maxLines - 1) {
          if (index < words.length - 1) {
            current += ' ' + words.slice(index + 1).join(' ');
          }
          break;
        }
      }
    }
    if (lines.length < maxLines && current) {
      lines.push(current);
    }

    return lines.slice(0, maxLines).map((line) => (
      line.length > maxChars + 6 ? `${line.slice(0, maxChars + 3)}…` : line
    ));
  }

  // ------------------------------------------------------------------ zoom

  function fitFlow() {
    if (!state.flow) {
      return;
    }
    const availableWidth = Math.max(160, els.flowScroll.clientWidth - 32);
    state.zoom = Math.min(1, Math.max(0.5, availableWidth / state.flow.width));
    applyZoom();
  }

  function changeZoom(factor) {
    if (!state.flow) {
      return;
    }
    state.zoom = Math.min(2.6, Math.max(0.3, state.zoom * factor));
    applyZoom();
  }

  function applyZoom() {
    if (!state.flow) {
      return;
    }
    const width = Math.round(state.flow.width * state.zoom);
    const height = Math.round(state.flow.height * state.zoom);
    els.flowSvg.setAttribute('width', String(width));
    els.flowSvg.setAttribute('height', String(height));
    els.flowSvg.style.width = `${width}px`;
    els.flowSvg.style.height = `${height}px`;
  }

  // ------------------------------------------------------------------ toast

  let toastTimer = null;

  function showToast(message) {
    els.toast.textContent = message;
    els.toast.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => els.toast.classList.add('hidden'), 3200);
  }
})();
