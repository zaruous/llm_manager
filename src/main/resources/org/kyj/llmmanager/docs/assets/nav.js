(function () {
  'use strict';

  // 서버 사이드에서 class="active"로 마킹된 링크를 뷰포트 내로 스크롤
  function scrollActiveIntoView() {
    var active = document.querySelector('.nav-tree a.active');
    if (active) {
      active.scrollIntoView({ block: 'nearest' });
      var details = active.closest('details');
      if (details) details.open = true;
    }
  }

  // 테마 토글
  function initTheme() {
    var btn = document.getElementById('themeBtn');
    if (!btn) return;
    btn.addEventListener('click', function () {
      var html = document.documentElement;
      var current = html.getAttribute('data-theme');
      html.setAttribute('data-theme', current === 'dark' ? 'light' : 'dark');
      try { localStorage.setItem('wiki-theme', html.getAttribute('data-theme')); } catch (e) {}
    });
    // 저장된 테마 복원
    try {
      var saved = localStorage.getItem('wiki-theme');
      if (saved) document.documentElement.setAttribute('data-theme', saved);
    } catch (e) {}
  }

  // 사이드바 검색 필터
  function initSearch() {
    var input = document.getElementById('searchInput');
    if (!input) return;
    input.addEventListener('input', function () {
      var q = input.value.trim().toLowerCase();
      document.querySelectorAll('.nav-tree li').forEach(function (li) {
        var text = li.textContent.toLowerCase();
        li.style.display = (!q || text.includes(q)) ? '' : 'none';
      });
      if (!q) {
        document.querySelectorAll('.nav-tree details').forEach(function (d) { d.open = true; });
      }
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    scrollActiveIntoView();
    initTheme();
    initSearch();
  });
})();
