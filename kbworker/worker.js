// 课表代理 Worker：服务端直连学校教务接口，避免境外/网络策略导致手机连不上。
// 学生自用，账号写在环境变量里。
const BASE = "http://222.243.161.213:81/hnrjzyxyhd";
const MAX_WEEK = 20;
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

function parseSecs(s) {
  const a = [];
  if (s && s.length > 1) {
    for (let i = 0; i < s.length - 1; i += 2) {
      const v = parseInt(s.substring(i + 1, Math.min(i + 3, s.length)), 10);
      if (!isNaN(v)) a.push(v);
    }
  }
  return a;
}
const ONLINE_KEYS = ["online","isOnline","onlineFlag","onlineTeach","onLine","teachMode","teachingMode","courseMode","classMode","teachType","teachingType","classType","courseType","teachForm","studyMode"];
const ONLINE_WORDS = ["线上","网络授课","网络教学","远程","直播","腾讯会议","钉钉","云课堂"];
function detectOnline(co) {
  for (const k of ONLINE_KEYS) {
    const v = co[k];
    if (v === 1 || v === true || v === "1") return true;
    if (typeof v === "string" && /^(1|true|yes|线上|网络)$/i.test(v.trim())) return true;
  }
  const blob = JSON.stringify(co);
  return ONLINE_WORDS.some(w => blob.includes(w));
}

async function jw(path, opts) {
  const r = await fetch(BASE + path, Object.assign({
    headers: { "User-Agent": UA, ...(opts && opts.headers || {}) }
  }, opts));
  const t = await r.text();
  return { status: r.status, text: t };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const USER = env.KEBIAO_USER_NO || "202405190231";
    const PWD  = env.KEBIAO_PWD_ENC;
    const SCH  = env.KEBIAO_SCHOOL_CODE || "4711";

    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET,OPTIONS",
      "Content-Type": "application/json; charset=utf-8",
    };
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });

    try {
      // 登录：GET 优先，POST 回退
      const q = `/login?userNo=${encodeURIComponent(USER)}&pwd=${encodeURIComponent(PWD)}&encode=1&captchaData=&codeVal=`;
      let lr = await jw(q, { method: "GET" });
      if (lr.status !== 200) lr = await jw(q, { method: "POST" });
      let lj = null;
      try { lj = JSON.parse(lr.text); } catch (e) { lj = null; }
      if (!lj) {
        return new Response(JSON.stringify({ ok:false, step:"login_json", status:lr.status, raw:lr.text.slice(0,300) }), { status:200, headers:cors });
      }
      if (lj.code !== "1") {
        return new Response(JSON.stringify({ ok:false, step:"login_code", code:lj.code, raw:lr.text.slice(0,300) }), { status:200, headers:cors });
      }
      const token = (lj.data && lj.data.token) || "";
      if (!token) return new Response(JSON.stringify({ ok:false, step:"no_token", raw:lr.text.slice(0,300) }), { status:200, headers:cors });

      if (url.pathname === "/whoami") {
        return new Response(JSON.stringify({ ok:true, token_len: token.length }), { status:200, headers:cors });
      }

      // 拉全部周次
      const weeks = {};
      let semester = "", currentWeek = 1;
      for (let w = 1; w <= MAX_WEEK; w++) {
        const arr = [];
        try {
          const rr = await jw(`/student/curriculum?week=${w}&kbjcmsid=`, {
            method: "GET",
            headers: { Token: token, schoolCode: SCH }
          });
          const j = JSON.parse(rr.text);
          if (j.code === "1") {
            const data = (j.data && j.data[0]) || (Array.isArray(j.data) ? j.data[0] : null);
            const top = data && data.topInfo && data.topInfo[0];
            if (w === 1) {
              semester = (top && top.semesterId) || "";
              currentWeek = parseInt((top && top.week) || "1", 10) || 1;
            }
            const cs = data && data.courses;
            if (cs) for (const co of cs) {
              arr.push({
                day: co.weekDay || 1,
                secs: parseSecs(co.classTime || ""),
                name: co.courseName || "",
                teacher: co.teacherName || "",
                room: co.classroomName || "",
                bld: co.buildingName || "",
                time: (co.startTime||"") + "-" + (co.endTIme||""),
                online: detectOnline(co),
              });
            }
          }
        } catch (e) { /* 该周失败留空 */ }
        weeks[String(w)] = arr;
      }
      return new Response(JSON.stringify({
        ok: true, weeks, semester, current_week: currentWeek, ts: Date.now()
      }), { status: 200, headers: cors });
    } catch (e) {
      return new Response(JSON.stringify({ ok:false, step:"exception", msg:String(e && e.message || e) }), { status:200, headers:cors });
    }
  }
};
