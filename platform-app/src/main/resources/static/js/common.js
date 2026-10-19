/* 公共：左侧导航栏（算法/T0 双轨菜单树，对齐真实产品菜单）+ API 工具 */
(function () {
  const menu = [
    { href: '/pages/index.html',     label: '总览',       key: 'index',     icon: '◎' },
    { group: '算法评价' },
    { href: '/pages/algo-overview.html', label: '算法概览', key: 'algo-overview', icon: '▤' },
    { href: '/pages/compare.html',   label: '算法对比',   key: 'compare',   icon: '⇄' },
    { href: '/pages/portrait.html',  label: '算法画像',   key: 'portrait',  icon: '◉' },
    { href: '/pages/vendor-portrait.html', label: '算法商画像', key: 'vendor-portrait', icon: '▣' },
    { href: '/pages/industry.html',  label: '行业均值',   key: 'industry',  icon: '≡' },
    { group: 'T0 评价' },
    { href: '/pages/t0-overview.html', label: 'T0 概览',  key: 't0-overview', icon: '◐' },
    { group: '交易分析' },
    { href: '/pages/correlation.html', label: '绩效相关性', key: 'correlation', icon: '⁘' },
    { href: '/pages/parent-metrics.html', label: '母单指标', key: 'parent-metrics', icon: '▦' },
    { href: '/pages/intraday.html',  label: '日内监控',   key: 'intraday',  icon: '⏱' },
    { group: '决策与智能' },
    { href: '/pages/recommend.html', label: '智能推荐',   key: 'recommend', icon: '✦' },
    { href: '/pages/ai.html',        label: 'AI 分析',    key: 'ai',        icon: '✧' },
  ];

  window.renderNav = function (activeKey) {
    const nav = document.createElement('div');
    nav.className = 'side-nav';
    nav.innerHTML = `
      <div class="side-logo"><span class="logo-mark">◈</span><span class="logo-text">智能算法评价平台</span></div>
      <div class="side-subtitle">Algo Execution Quality</div>
      <nav class="side-menu">
        ${menu.map(m => m.group
          ? `<div class="nav-group">${m.group}</div>`
          : `<a class="nav-link ${m.key === activeKey ? 'active' : ''}" href="${m.href}" title="${m.label}">
               <span class="nav-icon">${m.icon}</span><span class="nav-label">${m.label}</span>
             </a>`).join('')}
      </nav>
      <div class="side-footer">FullMW 轨道</div>`;
    document.body.prepend(nav);
    document.body.classList.add('has-sidenav');

    const style = document.createElement('style');
    style.textContent = '.nav-group{padding:14px 22px 4px;font-size:11px;letter-spacing:2px;'
        + 'color:rgba(139,155,184,.55);border-top:1px solid rgba(139,155,184,.12);margin-top:8px}'
        + '.nav-group:first-of-type{border-top:none;margin-top:0}';
    document.head.appendChild(style);

    /* 窗口尺寸变化时让 ECharts 图表自适应 */
    let resizeTimer;
    window.addEventListener('resize', () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        document.querySelectorAll('[_echarts_instance_]').forEach(el => {
          const inst = echarts.getInstanceByDom(el);
          inst && inst.resize();
        });
      }, 150);
    });
  };

  window.todayStr = function () {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  };

  window.api = async function (path) {
    const resp = await fetch(path);
    if (!resp.ok) throw new Error(path + ' → ' + resp.status);
    return resp.json();
  };

  /* 数字滚动动画 */
  window.countUp = function (el, target, duration = 900) {
    const start = performance.now();
    const isFloat = String(target).includes('.');
    function frame(now) {
      const t = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - t, 3);
      const val = target * eased;
      el.textContent = isFloat ? val.toFixed(2) : Math.round(val).toLocaleString();
      if (t < 1) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  };

  /* ECharts 深色基调 */
  window.chartBase = {
    textStyle: { color: '#8B9BB8' },
    grid: { left: 50, right: 24, top: 36, bottom: 32 },
  };
  window.CHART_COLORS = ['#22D3EE', '#A78BFA', '#FBBF24', '#34D399', '#F87171', '#3B82F6'];
})();
