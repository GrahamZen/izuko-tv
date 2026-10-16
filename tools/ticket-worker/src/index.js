// Worker 入口. 接口与规则见 tickets.js 开头的注释; 入口模块只能导出处理器, 逻辑都在 tickets.js.
import { handle } from "./tickets.js";
import { realGitHub } from "./github.js";
import { fakeGitHub } from "./fake-github.js";

// GitHub 客户端每个 Worker 实例建一个 (env 在实例里不变): 查状态的记忆挂在它上面, 跨请求留着
let gh = null;

export default {
  async fetch(request, env) {
    gh ??= env.FAKE_GITHUB === "1" ? fakeGitHub() : realGitHub(env);
    return handle(request, env, gh);
  },
};
