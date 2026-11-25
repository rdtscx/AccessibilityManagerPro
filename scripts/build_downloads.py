#!/usr/bin/env python3
# 生成统一样式（主站 CSS/动画）的二级下载页 downloads.html
import json, re, subprocess, html as H, sys

APPS = {
 'Android-AI-gateway': dict(title='AI 聚合网关', sub='把 Android 手机变成私有的 AI 模型聚合服务器，将多家上游账号统一翻译为 OpenAI 兼容端点。', up='alarmedvine/Android-AI-gateway', group=False),
 'sii-browser-backup': dict(title='Sii 浏览器', sub='用 Kotlin 与原生 XML 实现的 Android 浏览器，支持多标签、下载管理与 AI 助手。', up='wanwuwww/Sii', group=False),
 'AccessibilityManagerPro': dict(title='无障碍管理器 Pro', sub='基于 Android 系统无障碍服务的管理器，用于守护与恢复无障碍开关。', up='', group=False),
 'shurufa': dict(title='Fcitx5 输入法', sub='Fcitx5 输入法框架与引擎的 Android 移植版本，支持拼音、注音、粤拼、日语、韩语等多语言。', up='fcitx5-android/fcitx5-android', group=True),
}

STYLE = """
:root{
  --bg:#f7f8fa;--panel:#ffffff;--panel2:#fbfcfd;--line:#e6e9ef;--line2:#eef1f5;
  --text:#1e2733;--muted:#5f6b7a;--dim:#8d97a5;--accent:#3f6fd8;--accent-soft:#eef3fd;--ok:#16a34a;
  --shadow:0 1px 2px rgba(31,45,66,.03);--shadow-up:0 6px 18px rgba(24,38,63,.08);
  --mono:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono",monospace;
  --topbar-h:53px;--ease:cubic-bezier(.22,.9,.3,1);
}
html[data-theme="dark"]{
  --bg:#0f1319;--panel:#151b23;--panel2:#1a212b;--line:#232b36;--line2:#1e2631;
  --text:#e6ebf2;--muted:#9aa7b8;--dim:#6b7889;--accent:#7aa2f7;
  --accent-soft:rgba(122,162,247,.14);--ok:#4ade80;--shadow:0 1px 2px rgba(0,0,0,.3);
  --shadow-up:0 8px 22px rgba(0,0,0,.45);
}
*{box-sizing:border-box}
html{color-scheme:light dark;scroll-behavior:smooth;scroll-padding-top:calc(var(--topbar-h) + 12px)}
body{margin:0;line-height:1.62;color:var(--text);background:var(--bg);
  font-family:"Segoe UI",system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
  -webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;
  transition:background .24s var(--ease),color .24s var(--ease)}
a{color:inherit}
.topbar{position:sticky;top:0;z-index:20;background:color-mix(in srgb,var(--bg) 88%,transparent);
  backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
html[data-theme="dark"] .topbar{background:rgba(15,19,25,.86)}
.topbar-inner{max-width:1100px;margin:0 auto;display:flex;align-items:center;gap:12px;
  padding:11px 22px;min-height:var(--topbar-h)}
.brand{display:flex;align-items:center;gap:9px;text-decoration:none}
.logo{width:26px;height:26px;border-radius:7px;background:var(--accent-soft);color:var(--accent);
  font-weight:700;font-size:12px;display:grid;place-items:center;border:1px solid var(--line);flex-shrink:0}
.brand-text{display:flex;flex-direction:column;line-height:1.15}
.brand-name{font-size:12.5px;font-weight:600}
.brand-sub{font-size:10px;color:var(--dim)}
.topbar .path{margin-left:auto;font-family:var(--mono);font-size:10.5px;color:var(--dim);
  background:var(--panel2);border:1px solid var(--line);border-radius:999px;padding:2px 9px;white-space:nowrap}

.wrap{max-width:1100px;margin:0 auto;padding:26px 22px 56px}
header.top{padding:22px 0 8px}
header.top h1{font-size:24px;margin:0 0 8px;letter-spacing:-.01em}
header.top h1 .ver{font-family:var(--mono);font-size:12.5px;color:var(--accent);background:var(--accent-soft);
  border-radius:7px;padding:2px 8px;vertical-align:3px;margin-left:8px}
header.top p{margin:0;color:var(--muted);font-size:13px;max-width:820px}
.badges{display:flex;flex-wrap:wrap;gap:7px;margin-top:14px}
.badge{font-size:11px;color:var(--muted);background:var(--panel);border:1px solid var(--line);
  border-radius:999px;padding:3px 10px}
.badge b{color:var(--text);font-weight:600}

.sec-head{display:flex;align-items:center;flex-wrap:wrap;margin:26px 0 10px}
.sec-head h2{margin:0;font-size:14.5px;font-weight:600}
.count{font-size:10.5px;color:var(--dim);font-family:var(--mono);margin-left:7px}
.sec-head .grow{flex:1}

.index{background:var(--panel);border:1px solid var(--line);border-radius:12px;
  overflow:hidden;box-shadow:var(--shadow)}
.entry{border-top:1px solid var(--line2);animation:rise .34s var(--ease) both;
  animation-delay:calc(var(--i,0) * 26ms);transition:background .16s}
.entry:first-child{border-top:0}
.entry:hover{background:var(--panel2)}
.entry.open{background:var(--panel2)}
.entry-row{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:13px 18px;cursor:pointer}
.entry-main-inner{min-width:0;flex:1;display:block}
.vrow{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.vtag{font-family:var(--mono);font-size:13.5px;font-weight:700;color:var(--text)}
.tag{font-size:9.5px;color:var(--accent);background:var(--accent-soft);border-radius:999px;
  padding:1px 7px;white-space:nowrap}
.entry-desc{display:block;font-size:11.5px;color:var(--muted);margin-top:3px;
  font-family:var(--mono);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.entry-right{display:flex;align-items:center;gap:9px;flex-shrink:0}
.btn{display:inline-flex;align-items:center;gap:6px;font-size:11.5px;font-weight:600;
  color:var(--accent);background:var(--accent-soft);border:1px solid transparent;border-radius:8px;
  padding:5px 11px;text-decoration:none;white-space:nowrap;
  transition:background .15s,color .15s,transform .12s var(--ease)}
.btn:hover{background:var(--accent);color:#fff}
.btn:active{transform:scale(.94)}
html[data-theme="dark"] .btn:hover{color:#0f1319}
.btn svg{width:12px;height:12px}
.btn.ghost{background:var(--panel);border-color:var(--line);color:var(--muted);font-weight:500}
.btn.ghost:hover{background:var(--accent);color:#fff;border-color:transparent}
.tgl{font:inherit;font-size:10.5px;color:var(--muted);background:var(--panel);
  border:1px solid var(--line);border-radius:8px;padding:4px 9px;cursor:pointer;
  transition:border-color .15s,color .15s,transform .12s var(--ease)}
.tgl:hover{color:var(--text);border-color:var(--accent-soft)}
.chev{display:inline-block;width:6px;height:6px;border-top:1.5px solid currentColor;
  border-right:1.5px solid currentColor;transform:rotate(45deg);margin-left:4px;vertical-align:1px;
  transition:transform .2s var(--ease)}
.entry.open .chev{transform:rotate(135deg)}

.notes{max-height:0;overflow:hidden;transition:max-height .32s var(--ease)}
.entry.open .notes{max-height:1400px}
.notes-inner{padding:0 18px 15px;font-size:12.5px;color:var(--muted);line-height:1.7}
.notes-inner ul{margin:6px 0 0;padding-left:18px}
.notes-inner li{margin:3px 0}
.notes-inner p{margin:5px 0}
.notes-inner code{font-family:var(--mono);font-size:11.5px;background:var(--accent-soft);
  color:var(--accent);border-radius:4px;padding:1px 5px}
.notes-inner .cap{font-size:10.5px;color:var(--dim);font-family:var(--mono);
  text-transform:uppercase;letter-spacing:.03em}

.files{display:flex;flex-wrap:wrap;gap:6px;padding:2px 18px 15px}
.files .f{display:inline-flex;align-items:center;gap:5px;font-size:10.5px;font-family:var(--mono);
  color:var(--muted);background:var(--panel);border:1px solid var(--line);border-radius:7px;
  padding:3px 8px;text-decoration:none;transition:color .15s,border-color .15s,transform .12s var(--ease)}
.files .f:hover{color:var(--accent);border-color:var(--accent-soft)}
.files .f:active{transform:scale(.96)}
.files .f b{font-weight:600;color:var(--text)}
.files .f.plugin{background:var(--panel2)}

footer{color:var(--dim);font-size:10.5px;margin-top:28px;line-height:1.8;
  border-top:1px solid var(--line);padding-top:14px}
footer p{margin:3px 0}
footer a{color:var(--muted);text-decoration:none;border-bottom:1px solid var(--line);
  transition:color .15s,border-color .15s}
footer a:hover{color:var(--accent);border-bottom-color:var(--accent-soft)}
.back{margin-top:14px;font-size:11.5px;display:flex;flex-wrap:wrap;gap:14px}
.back a{color:var(--accent);border-bottom-color:transparent}
.back a:hover{border-bottom-color:var(--accent-soft)}

@keyframes rise{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
@media (prefers-reduced-motion:reduce){
  .entry,.chev,.btn,.files .f,.tgl,.notes,.topbar{animation:none!important;transition:none!important}
  .notes{max-height:none}
}
@media (max-width:640px){
  .entry-desc{white-space:normal}
  .entry-right{flex-direction:column;align-items:flex-end}
  .entry-right .btn{order:-1}
}
"""

THEME_JS = """<script>
(function(){
  var dark=false;
  try{dark=!!(window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches);}catch(e){}
  document.documentElement.setAttribute('data-theme',dark?'dark':'light');
  if(window.matchMedia){var mq=window.matchMedia('(prefers-color-scheme: dark)');
    var on=function(e){document.documentElement.setAttribute('data-theme',e.matches?'dark':'light');};
    mq.addEventListener?mq.addEventListener('change',on):mq.addListener&&mq.addListener(on);}
})();
</script>"""

TOGGLE_JS = """<script>
document.querySelectorAll('.entry-row').forEach(function(row){
  row.addEventListener('click',function(e){
    if(e.target.closest('.btn')) return;
    var item=row.parentElement; item.classList.toggle('open');
    var t=item.querySelector('.tgl'); if(t) t.setAttribute('aria-expanded', item.classList.contains('open'));
  });
});
</script>"""

DL_ICON = ('<svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">'
           '<path d="M2.75 14A1.75 1.75 0 0 1 1 12.25v-2.5a.75.75 0 0 1 1.5 0v2.5c0 .138.112.25.25.25h10.5a.25.25 0 0 0 .25-.25v-2.5a.75.75 0 0 1 1.5 0v2.5A1.75 1.75 0 0 1 13.25 14Z"/>'
           '<path d="M7.25 7.689V2a.75.75 0 0 1 1.5 0v5.689l1.97-1.969a.749.749 0 1 1 1.06 1.06l-3.25 3.25a.749.749 0 0 1-1.06 0L4.22 6.78a.749.749 0 1 1 1.06-1.06l1.97 1.969Z"/></svg>')


def fmt_size(b):
    if not b: return '—'
    if b < 1024: return '%d B' % b
    if b < 1024 * 1024: return '%.0f KB' % (b / 1024)
    return '%.1f MB' % (b / 1024 / 1024)


def fmt_date(s): return (s or '')[:10]


def inline(s):
    s = H.escape(s, quote=False)
    s = re.sub(r'`([^`]+?)`', r'<code>\1</code>', s)
    s = re.sub(r'\*\*([^*]+?)\*\*', r'<b>\1</b>', s)
    s = re.sub(r'\[([^\]]+)\]\((https?://[^)]+)\)', r'<a href="\2">\1</a>', s)
    return s


def md_to_html(text):
    text = (text or '').strip()
    if not text: return ''
    out, in_ul = [], False
    for raw in text.splitlines():
        line = raw.strip()
        if not line: continue
        if line.startswith('#'):
            if in_ul: out.append('</ul>'); in_ul = False
            out.append('<p class="cap">%s</p>' % inline(line.lstrip('#').strip()))
            continue
        if line[:2] in ('- ', '* '):
            if not in_ul: out.append('<ul>'); in_ul = True
            out.append('<li>%s</li>' % inline(line[2:])); continue
        if in_ul: out.append('</ul>'); in_ul = False
        if line.startswith('>'): line = line.lstrip('>').strip()
        out.append('<p>%s</p>' % inline(line))
    if in_ul: out.append('</ul>')
    return '\n'.join(out)


def vkey(tag): return tuple(int(n) for n in re.findall(r'\d+', tag or ''))


def api_releases(repo):
    data = json.loads(subprocess.check_output(
        ['gh', 'api', 'repos/rdtscx/%s/releases?per_page=100' % repo], stderr=subprocess.DEVNULL)) if True else []
    out = []
    for r in data:
        if r.get('draft') or r.get('prerelease'): continue
        apks = [a for a in (r.get('assets') or []) if a['name'].lower().endswith('.apk')]
        if not apks: continue
        out.append(dict(tag=r['tag_name'], date=r.get('published_at') or '', body=r.get('body') or '', apks=apks))
    out.sort(key=lambda x: vkey(x['tag']), reverse=True)
    return out


def short(name):
    n = re.sub(r'\.apk$', '', name)
    n = re.sub(r'-\d+\.\d+\.\d+.*$', '', n)
    n = n.replace('org.fcitx.fcitx5.android.plugin.', '').replace('org.fcitx.fcitx5.android', 'fcitx5')
    return n


def build(repo, app):
    rels = api_releases(repo)
    if not rels:
        raise SystemExit('%s 没有可用 Release' % repo)
    entries = []
    for i, r in enumerate(rels):
        tag, date, body = r['tag'], r['date'], r['body']
        newest = i == 0
        main_apks = [a for a in r['apks'] if 'plugin' not in a['name']] if app['group'] else r['apks']
        plug = [a for a in r['apks'] if 'plugin' in a['name']] if app['group'] else []
        btns, chips = [], []
        if app['group']:
            for a in main_apks:
                abi = re.search(r'-(arm64-v8a|armeabi-v7a|x86_64|x86)-release', a['name'])
                label = abi.group(1) if abi else short(a['name'])
                cls = 'btn' + ('' if abi and abi.group(1) == 'arm64-v8a' else ' ghost')
                btns.append('<a class="%s" href="%s" download>%s%s</a>' % (
                    cls, a['browser_download_url'], DL_ICON, '下载 ' + label))
            for a in plug:
                chips.append('<a class="f plugin" href="%s" download title="%s">%s</a>' % (
                    a['browser_download_url'], H.escape(a['name']), H.escape(short(a['name']))))
        else:
            for a in main_apks:
                btns.append('<a class="btn" href="%s" download>%s下载 %s</a>' % (
                    a['browser_download_url'], DL_ICON, H.escape(tag)))
                chips.append('<a class="f" href="%s" download title="%s">%s <b>%s</b></a>' % (
                    a['browser_download_url'], H.escape(a['name']), H.escape(a['name']), fmt_size(a.get('size'))))
        total = sum(a.get('size', 0) for a in r['apks'])
        meta = '%s · %d 个文件 · %s' % (fmt_date(date) or '未知日期', len(r['apks']), fmt_size(total))
        notes = md_to_html(body)
        entries.append('''
    <div class="entry{open_}" style="--i:{i}">
      <div class="entry-row">
        <div class="entry-main-inner">
          <div class="vrow"><span class="vtag">{tag}</span>{badge}<span class="tag">{nfiles} 个文件</span></div>
          <span class="entry-desc">{meta}</span>
        </div>
        <div class="entry-right">
          {btns}
          <button class="tgl" type="button" aria-expanded="false">更新记录<span class="chev"></span></button>
        </div>
      </div>
      {chips}
      <div class="notes"><div class="notes-inner">{notes}</div></div>
    </div>'''.format(open_='', i=i, tag=H.escape(tag),
                     badge='<span class="tag">最新</span>' if newest else '',
                     nfiles=len(r['apks']), meta=H.escape(meta),
                     btns=''.join(btns), chips=('<div class="files">%s</div>' % ''.join(chips)) if chips else '',
                     notes=notes or '<p>本版本未提供更新说明。</p>'))

    latest = rels[0]['tag']
    updated = fmt_date(rels[0]['date'])
    up = app['up']
    return '''<!doctype html>
<html lang="zh-CN" data-theme="light">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
{theme}
<title>全部版本下载 · {title}</title>
<style>{style}</style>
</head>
<body>
<div class="topbar"><div class="topbar-inner">
  <a class="brand" href="https://rdtscx.github.io/">
    <span class="logo">R</span>
    <span class="brand-text"><span class="brand-name">rdtscx</span><span class="brand-sub">安装包下载</span></span>
  </a>
  <span class="path">/{repo}/downloads.html</span>
</div></div>

<div class="wrap">
  <header class="top">
    <h1>{title}<span class="ver">{latest}</span></h1>
    <p>{sub}</p>
    <div class="badges">
      <span class="badge">共 <b>{n}</b> 个版本</span>
      <span class="badge">最新 <b>{latest}</b></span>
      <span class="badge">更新于 <b>{updated}</b></span>
      <span class="badge">安装包托管 <b>本仓库 Releases</b></span>
    </div>
  </header>

  <div class="sec-head"><h2>全部版本</h2><span class="count">{n} / {n}</span></div>
  <div class="index">{entries}</div>

  <footer>
    <p>仓库：<a href="https://github.com/{repo}">{repo}</a>{up_part}</p>
    <div class="back">
      <a href="./">← 返回站点首页</a>
      <a href="https://github.com/{repo}/releases">仓库 Releases</a>
      <a href="https://rdtscx.github.io/">返回主站</a>
    </div>
  </footer>
</div>
{toggle}
</body>
</html>
'''.format(theme=THEME_JS, title=app['title'], style=STYLE, repo=repo, sub=H.escape(app['sub']),
           latest=H.escape(latest), updated=updated, n=len(rels), entries=''.join(entries),
           up_part=(' · 上游：<a href="https://github.com/%s">%s</a>' % (up, up)) if up else '',
           toggle=TOGGLE_JS)


if __name__ == '__main__':
    repo = sys.argv[1]
    app = APPS[repo]
    out = '/home/user/work/%s/downloads.html' % repo
    with open(out, 'w', encoding='utf-8') as f:
        f.write(build(repo, app))
    print('写入', out)
