"""regroup.py 的分组计划模板: 复制到临时目录 (别放进仓库) 改成这次的计划.

序号 = BASE..main 的原序, 1 起 (cells.py / tested.py 打印的 idx).
"""

# 上游基点: fork 栈从它之后开始
BASE = "878f565934"

PLAN_UNITS = [
    # anchor = 组落在原历史里哪一条的位置; members = 整条并进来的提交, 按原序叠.
    # 往前挪 (成员序号 > anchor) 冲突时自动留在原位单独成段; 往后挪冲突直接报错.
    {"anchor": 60, "members": [52, 60, 62, 67], "msg": "base"},
    # 小数锚点 = 两条之间 / 链尾之后; separate = 只挪位置, 每条照旧单独一条.
    {"anchor": 60.5, "members": [56], "separate": True},
    # template_head: 发版说明模板写成原链尾的版本 (其余各步都拿掉模板), 一般放链尾之后那组 docs.
    {"anchor": 190.5, "members": [168, 186], "msg": "docs", "template_head": True, "author": 186},
]

# 一条提交按文件拆给几个目标: 路径 -> 目标序号, "*" = 其余路径, "latest" = 此前最后一条改这个文件的提交.
SPLITS = {
    # 69: {"*": 60, "app/shared/ui-tv/src/commonMain/kotlin/ui/profile/TvUserProfilePicker.kt": 68},
}

# 说明: text = 新写的正文; 只给 subject = 换标题、正文用 anchor 那条的; 不写 = 用 anchor 那条的原说明.
# 合并提交的说明照提交规矩写: 标题 + 两三段「改了什么 + 为什么」, 不拼成员原文.
MESSAGES = {
    "base": {},
    "docs": {"subject": "docs: 1.0.6 更新说明", "text": "发版说明模板的当前版本。"},
}

# 拆过来的部分在说明末尾加一句 (不需要就删掉)
# SPLIT_NOTE = "含追上游后 {files} 的接口适配."
