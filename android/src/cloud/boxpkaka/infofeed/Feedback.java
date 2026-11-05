package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一件事上的反馈报给服务器（POST /v1/events/{id}/feedback）：想多看这类、不需要（可以带原因）、撤回。后台发，失败只记日志。
 * 服务器按反馈记下或加强了记忆时，回应里带着 memory（added、updated、related）；跟用户说记住了什么用 remembered 和
 * wantNote，各处说法一致。
 */
final class Feedback {
    /** 服务器的回应，没发出去时是 null。在界面线程上调，页面已经关了就不调。 */
    interface Done {
        void on(JSONObject response);
    }

    private Feedback() {}

    /** 想多看这类：服务器按这件事的话题记下或加强一条长期关注。 */
    static void want(Activity a, long id, Done done) {
        send(a, id, "useful", "topic", null, done);
    }

    /** 不需要。reason 是选的原因（没选时为 null），topic 是选中的“不想看这类”{text, keywords}，原样传回服务器记下。 */
    static void notNeeded(Activity a, long id, String reason, JSONObject topic, Done done) {
        send(a, id, "not_needed", reason, topic, done);
    }

    /** 撤回这件事上的反馈：恢复不需要的、取消想多看。当时因原因记下的记忆由服务器一起删掉。 */
    static void clear(Activity a, long id, Done done) {
        send(a, id, null, null, null, done);
    }

    private static void send(Activity a, long id, String value, String reason, JSONObject topic, Done done) {
        Context app = a.getApplicationContext();
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = Api.post(app, "/v1/events/" + id + "/feedback", new JSONObject()
                        .put("value", value == null ? JSONObject.NULL : value).putOpt("reason", reason).putOpt("topic", topic));
            } catch (Exception err) {
                Trace.log(app, "feedback.fail", "id=" + id + " " + (value == null ? "clear" : value) + " " + err);
            }
            if (done == null) return;
            JSONObject got = r;
            a.runOnUiThread(() -> {
                if (!a.isDestroyed()) done.on(got);
            });
        }, "feedback").start();
    }

    /** 这次新记下的那条记忆，说成“记住了：…”；没有新记下的返回 null。 */
    static String remembered(JSONObject r) {
        JSONObject memory = r == null ? null : r.optJSONObject("memory");
        JSONArray added = memory == null ? null : memory.optJSONArray("added");
        return added != null && added.length() > 0 ? "记住了：" + added.optJSONObject(0).optString("text") : null;
    }

    /** “想多看”之后的提示：记下或加强了哪条记忆，说成“以后多帮你留意「…」”；都没有时说这类内容。 */
    static String wantNote(JSONObject r) {
        JSONObject m = r.optJSONObject("memory");
        for (String key : new String[]{"added", "updated", "related"}) {
            JSONArray items = m == null ? null : m.optJSONArray(key);
            if (items != null && items.length() > 0) {
                return "好的，以后多帮你留意「" + MemoryBubbles.label(items.optJSONObject(0).optString("text")) + "」";
            }
        }
        return "好的，以后多帮你留意这类内容";
    }
}
