"""按行认身份重建一个文件: 三方合并冲突时的退路 (regroup.py 落地, movable.py 预判).

三方合并把挨着的改动算冲突, 换了先后顺序的插入也合不上 (文案文件、控制台文案表大家都往同一处加行).
给一段历史里每一行记下由哪条提交加、哪条删, 之后按「已放上去了哪些提交的改动」就能拼出这个文件.
条件: 放上去的提交删改的行, 加这些行的提交也已放上去; 不满足返回 False, 调用方照旧算冲突.
提交放上去的先后与原历史不同, 拼出来的中间版本里两段从没同时存在过的行先后不定; 位置对不对由终树校验兜底.
"""
import difflib


class LineHistory:
    def __init__(self, base, versions):
        """base: 段首的内容 (bytes, None = 文件不在); versions: [(提交序号, 这条提交之后的内容 bytes 或 None), …] 按原顺序.
        文件在段里删了又建时抛 ValueError."""
        self.ids, self.text, self.intro, self.dele = [], {}, {}, {}
        self.creator = self.remover = None
        cur = []
        if base is not None:
            cur = [self._add(ln, 0) for ln in base.splitlines(keepends=True)]
            self.ids = list(cur)
        exists = base is not None
        for k, data in versions:
            if data is None:
                for x in cur:
                    self.dele[x] = k
                self.remover, cur, exists = k, [], False
                continue
            if not exists:
                if self.remover is not None:
                    raise ValueError("文件删了又建")
                self.creator, exists = k, True
            old = [self.text[x] for x in cur]
            new = data.splitlines(keepends=True)
            nxt = []
            for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
                if tag == "equal":
                    nxt += cur[i1:i2]
                    continue
                for x in cur[i1:i2]:
                    self.dele[x] = k
                added = [self._add(ln, k) for ln in new[j1:j2]]
                at = self.ids.index(cur[i2]) if i2 < len(cur) else len(self.ids)
                self.ids[at:at] = added
                nxt += added
            cur = nxt

    def _add(self, line, k):
        x = len(self.text)
        self.text[x], self.intro[x] = line, k
        return x

    def content(self, done):
        """done = 已放上去的提交序号 (段首之前的不用列). 返回 bytes; None = 文件不在; False = 条件不满足."""
        for x in self.ids:
            d = self.dele.get(x)
            if d in done and self.intro[x] and self.intro[x] not in done:
                return False
        if self.creator is not None and self.creator not in done:
            # 文件还没建出来却已有别的提交往里加的行: 那些行会被算进建文件的那条提交, 不行
            if any(self.intro[x] in done for x in self.ids):
                return False
            return None
        if self.remover is not None and self.remover in done:
            return None
        return b"".join(self.text[x] for x in self.ids
                        if (self.intro[x] == 0 or self.intro[x] in done) and self.dele.get(x) not in done)
