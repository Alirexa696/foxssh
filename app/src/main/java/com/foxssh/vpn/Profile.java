package com.foxssh.vpn;

import org.json.JSONException;
import org.json.JSONObject;

/** یک کانفیگ SSH. */
public final class Profile {
    public String id = "";
    public String name = "";
    public String host = "";
    public int port = 22;
    public String user = "";
    public String pass = "";
    public int udpgw = 7300;

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
                .put("user", user).put("pass", pass).put("udpgw", udpgw);
    }

    public static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optString("id");
        p.name = o.optString("name");
        p.host = o.optString("host");
        p.port = o.optInt("port", 22);
        p.user = o.optString("user");
        p.pass = o.optString("pass");
        p.udpgw = o.optInt("udpgw", 7300);
        return p;
    }

    public String title() {
        return name.isEmpty() ? host : name;
    }
}
