"""layers.py 的分层计划模板: 复制到临时目录 (别放进仓库) 改成这次的计划."""

# 要重排的分支 (在检出它的工作区里跑), 与留着不动的最后一条提交: 各层接在它后面
BRANCH = "feat/some-feature"
KEEP = "d49fca8bea"

# 工作区里没提交的改动默认都算进最终的树; 匹配这些的留在工作区不提交 (正则, 对路径 search)
EXCLUDE = [
    r"^\.agents/", r"^scripts/(history|review)/",  # 智能体与工具归 main 上 chore(agents) 那一条
    r"^ci-helper/release-template\.md$",  # 发版说明模板只改工作区
    r"^[^/]+\.png$",  # 根目录的截图
]

# 层的先后 = 提交的先后: 下面的层不用上面的层的东西, 每一条都要编得过
LAYERS = ["engine", "domain", "ui", "docs"]

# (层, 路径正则): 从上到下第一条匹配的算数; 有路径没匹配上就报错
RULES = [
    ("engine", r"^app/shared/source-prep/"),
    ("domain", r"/domain/media/sourceprep/"),
    ("ui", r"^app/shared/ui-tv/"),
    ("docs", r"^docs/"),
]

# 个别文件改到别的层: 逐条编的时候编不过的, 挪到它依赖的那层, 写上原因
MOVES = {
    # "app/shared/app-data/src/commonTest/kotlin/domain/SomeTableTest.kt": "domain",  # 用领域层的类型造数据
}

# (层, 历史提交, 路径): 这一层先放这个文件在那条提交里的样子, 它最终的样子归后面的层.
# 给一个文件里几行归前面的层、几行归后面的层的情况用 (如依赖注入的登记).
EARLY = [
    # ("engine", "87033ef9f9", "app/shared/app-data/src/commonMain/kotlin/domain/usecase/UseCaseModules.kt"),
]

# 备份 tag 的名字; 不写 = backup-regroup-<日期>-<分支名最后一段>
# BACKUP_TAG = "backup-regroup-20261113-some-feature"

# 每层的说明, 照提交规矩写: 标题 + 两三段「改了什么 + 为什么」, 末尾写这一层的文件在哪 (以后按层定位用)
MESSAGES = {
    "engine": """feat(scope): 标题

正文。

在哪: …
""",
}
