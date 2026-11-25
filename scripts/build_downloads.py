#!/usr/bin/env python3
# 生成映射页底部的下载区块并注入 index.html（数据只取本仓库 Release）
# 生成映射页底部的下载区块（内联到各仓库 index.html 页尾）
# 数据只取本仓库 Release，占位样式自包含，不影响宿主页面
import json, re, os, subprocess, sys, html as H

APPS = {
 'Android-AI-gateway': dict(title='AI 聚合网关', up='alarmedvine/Android-AI-gateway', group=False),
 'sii-browser-backup': dict(title='Sii 浏览器', up='wanwuwww/Sii', group=False),
 'AccessibilityManagerPro': dict(title='无障碍管理器 Pro', up='', group=False),
 'shurufa': dict(title='Fcitx5 输入法', up='fcitx5-android/fcitx5-android', group=True),
}

CSS = """
<style>
.dlx{margin:34px 0 8px;color:var(--dlx-text,#1e2733)}
.dlx *{box-sizing:border-box}
.dlx-head{display:flex;align-items:baseline;gap:9px;margin:0 0 10px}
.dlx-head h2{margin:0;font-size:19px;font-weight:600;letter-spacing:-.01em;color:inherit}
.dlx-head .dlx-count{font-size:11px;color:#8d97a5;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.dlx-note{margin:0 0 14px;font-size:12px;color:#5f6b7a}
.dlx-list{border:1px solid var(--dlx-line,#e6e9ef);border-radius:12px;overflow:hidden;
  background:var(--dlx-panel,#fff);box-shadow:0 1px 2px rgba(31,45,66,.03)}
.dlx-item{border-top:1px solid var(--dlx-line2,#eef1f5);animation:dlxRise .34s cubic-bezier(.22,.9,.3,1) both;
  animation-delay:calc(var(--i,0) * 24ms)}
.dlx-item:first-child{border-top:0}
.dlx-sum{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 16px;
  cursor:pointer;list-style:none;transition:background .16s}
.dlx-sum::-webkit-details-marker{display:none}
.dlx-sum:hover{background:var(--dlx-panel2,#fbfcfd)}
.dlx-left{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-width:0}
.dlx-ver{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:14px;font-weight:700;color:inherit}
.dlx-badge{font-size:9.5px;font-weight:600;color:#3f6fd8;background:#eef3fd;border-radius:999px;padding:1px 7px;white-space:nowrap}
.dlx-date{font-size:11px;color:#8d97a5;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.dlx-right{display:flex;align-items:center;gap:10px;flex-shrink:0;font-size:11px;color:#8d97a5}
.dlx-arrow{width:6px;height:6px;border-top:1.5px solid #8d97a5;border-right:1.5px solid #8d97a5;
  transform:rotate(45deg);transition:transform .2s}
details[open] .dlx-arrow{transform:rotate(135deg)}
.dlx-files{display:flex;flex-wrap:wrap;gap:6px;padding:2px 16px 14px}
.dlx-f{display:inline-flex;flex-direction:column;gap:1px;max-width:280px;text-decoration:none;
  border:1px solid var(--dlx-line,#e6e9ef);border-radius:8px;padding:5px 9px;background:#fff;
  transition:border-color .15s,background .15s,transform .12s}
.dlx-f:hover{border-color:#3f6fd8;background:#eef3fd;transform:translateY(-1px)}
.dlx-f b{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:10.5px;font-weight:600;
  color:#1e2733;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:260px}
.dlx-f span{font-size:10px;color:#8d97a5}
.dlx-foot{margin-top:12px;font-size:11px;color:#8d97a5}
.dlx-foot a{color:#3f6fd8;text-decoration:none}
@media (prefers-color-scheme:dark){
  .dlx{--dlx-text:#e6ebf2;--dlx-panel:#151b23;--dlx-panel2:#1a212b;--dlx-line:#232b36;--dlx-line2:#1e2631}
  .dlx-head h2{color:#e6ebf2}
  .dlx .dlx-ver{color:#e6ebf2}
  .dlx-f{background:#1a212b;border-color:#232b36}
  .dlx-f:hover{background:#1f2a3d;border-color:#7aa2f7}
  .dlx-f b{color:#e6ebf2}
  .dlx-badge{color:#7aa2f7;background:rgba(122,162,247,.14)}
  .dlx-foot a,.dlx-f:hover b{color:#7aa2f7}
  .dlx-note,.dlx-date,.dlx-right,.dlx-foot{color:#9aa7b8}
}
@keyframes dlxRise{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
@media (prefers-reduced-motion:reduce){.dlx-item{animation:none!important}}
.dlx-notes{margin:0 16px 12px;padding:10px 12px;border-left:2px solid #3f6fd8;background:var(--dlx-panel2,#fbfcfd);
  border-radius:0 8px 8px 0;font-size:12px;line-height:1.7;color:#5f6b7a;white-space:pre-wrap}
.dlx-notes span{display:block;margin-top:6px;font-size:11px}
@media (prefers-color-scheme:dark){.dlx-notes{background:#1a212b;color:#9aa7b8}}
</style>
"""


def short_label(name):
    """把 fcitx 的 APK 名压成 主程序/插件 + ABI；插件本身不分 ABI。"""
    m = re.match(r'org\.fcitx\.fcitx5\.android(?:\.plugin\.([a-z0-9_\-]+?))?-\d', name)
    if not m:
        return name
    base = (m.group(1) or '主程序').replace('_', '-')
    abi = ''
    for a in ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'):
        if ('-' + a + '-release') in name:
            abi = a
            break
    return '%s · %s' % (base, abi) if abi else base


def fmt_size(b):
    if not b: return '—'
    if b < 1024: return '%d B' % b
    if b < 1024 * 1024: return '%.0f KB' % (b / 1024)
    return '%.1f MB' % (b / 1024 / 1024)


def vkey(t): return tuple(int(n) for n in re.findall(r'\d+', t or ''))


def releases(repo):
    full = repo if '/' in repo else 'rdtscx/' + repo
    data = json.loads(subprocess.check_output(['gh', 'api', 'repos/%s/releases?per_page=100' % full]))
    out = []
    for r in data:
        if r.get('draft') or r.get('prerelease'): continue
        assets = r.get('assets') or []
        if not assets: continue
        out.append(dict(tag=r['tag_name'], date=r.get('published_at') or '', assets=assets, body=r.get('body') or ''))
    out.sort(key=lambda x: vkey(x['tag']), reverse=True)
    return out


CSS_NOTE = """
.dlx-notes{margin:0 16px 12px;padding:10px 12px;border-left:2px solid #3f6fd8;background:var(--dlx-panel2,#fbfcfd);
  border-radius:0 8px 8px 0;font-size:12px;line-height:1.7;color:#5f6b7a;white-space:pre-wrap}
.dlx-notes span{display:block;margin-top:6px;font-size:11px}
@media (prefers-color-scheme:dark){.dlx-notes{background:#1a212b;color:#9aa7b8}}
"""


def notes_html(body, repo, tag):
    if not body:
        return ''
    lines = []
    for raw in body.splitlines():
        t = raw.strip()
        if not t or t.startswith('![') or t.startswith('|') or t.startswith('---'):
            continue
        t = re.sub(r'!\[[^\]]*\]\([^)]*\)', '', t)
        t = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', t)
        t = re.sub(r'^#+\s*', '', t)
        t = re.sub(r'^[*+-]\s*', '· ', t)
        t = t.replace('**', '').replace('`', '')
        if t.strip():
            lines.append(t.strip())
        if len(lines) >= 4:
            break
    if not lines:
        return ''
    text = '\n'.join(lines)
    if len(text) > 420:
        text = text[:420] + '…'
    return '<div class="dlx-notes">%s<span>完整更新记录见 <a href="https://github.com/%s/releases/tag/%s">本仓库 Release</a></span></div>' % (
        H.escape(text), repo, H.escape(tag))


def build(repo, app):
    rels = releases(repo)
    total_files = sum(len(r['assets']) for r in rels)
    items = []
    for i, r in enumerate(rels):
        def chip(a):
            label = short_label(a['name']) if app['group'] else a['name']
            return ('<a class="dlx-f" href="%s" download title="%s"><b>%s</b><span>%s</span></a>' % (
                a['browser_download_url'], H.escape(a['name']), H.escape(label), fmt_size(a.get('size'))))
        files = ''.join(chip(a) for a in sorted(r['assets'], key=lambda a: a['name']))
        notes = notes_html(r['body'], repo, r['tag'])
        size = fmt_size(sum(a.get('size', 0) for a in r['assets']))
        heading = ('<span class="dlx-left"><span class="dlx-ver">%s</span>%s<span class="dlx-date">%s</span></span>'
                   '<span class="dlx-right">%d 个文件 · %s<span class="dlx-arrow"></span></span>') % (
            H.escape(r['tag']), '<span class="dlx-badge">最新</span>' if i == 0 else '', (r['date'] or '')[:10],
            len(r['assets']), size)
        if i == 0:
            items.append('<div class="dlx-item" style="--i:0"><div class="dlx-sum">%s</div>'
                         '<div class="dlx-files">%s</div>%s</div>' % (heading, files, notes))
        else:
            items.append('<details class="dlx-item" style="--i:%d"><summary class="dlx-sum">%s</summary>'
                         '<div class="dlx-files">%s</div>%s</details>' % (i, heading, files, notes))

    notice = ('下面的文件全部托管在本仓库 Releases：主程序与各语言插件 APK 按版本排列，最新版本默认展开。'
              if app['group'] else '下面的安装包全部托管在本仓库 Releases：按版本排列，最新版本默认展开。')
    up = app['up']
    return CSS + '''
<div class="dlx" id="download">
  <div class="dlx-head"><h2>下载</h2><span class="dlx-count">%d 个版本 · %d 个文件</span></div>
  <p class="dlx-note">%s</p>
  <div class="dlx-list">%s</div>
  <p class="dlx-foot">仓库：<a href="https://github.com/rdtscx/%s">rdtscx/%s</a>%s</p>
</div>
''' % (len(rels), total_files, notice, ''.join(items), repo, repo,
       (' · 上游：<a href="https://github.com/%s">%s</a>' % (up, up)) if up else '')


if __name__ == '__main__':
    repo = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('GITHUB_REPOSITORY', '').split('/')[-1]
    block = build(repo, APPS[repo])
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    index = os.path.join(root, 'index.html')
    src = open(index, encoding='utf-8').read()
    START, END = '<!-- DLX:START -->', '<!-- DLX:END -->'
    if START in src and END in src:
        a = src.index(START); b = src.index(END) + len(END)
        src = src[:a] + START + block + END + src[b:]
    else:
        i = src.rfind('<footer')
        if i < 0:
            i = src.rfind('</body>')
        src = src[:i] + START + block + END + '\n' + src[i:]
    open(index, 'w', encoding='utf-8').write(src)
    print('已更新 %s' % index)
