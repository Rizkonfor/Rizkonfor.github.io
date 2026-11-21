package id.rizkon.academypronova;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Memeriksa notifikasi baru dari Code.gs (?action=notif) kira-kira tiap 15 menit. */
public class NotifJobService extends JobService {

    static final String PREFS = "ssb_notif";
    static final String CHANNEL = "ssb_updates";
    private static final int JOB_ID = 1001;
    private static final long MARGIN_MS = 6L * 60 * 60 * 1000;

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_ID) return;
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, NotifJobService.class))
                .setPeriodic(15L * 60 * 1000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Kabar akademi", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Jadwal, hasil laga, absensi, catatan pelatih, dan pendaftaran orang tua");
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(getApplicationContext());
                } catch (Throwable t) {
                    android.util.Log.w("SSB", "Cek notifikasi gagal, dicoba lagi nanti", t);
                }
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static long parseIso(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    static void check(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String url = sp.getString("url", "");
        String token = sp.getString("token", "");
        if (url.isEmpty() || token.isEmpty()) return;
        long registered = sp.getLong("registered", System.currentTimeMillis());
        long maxAt = sp.getLong("maxAt", 0L);
        long since = Math.max(registered, maxAt - MARGIN_MS);

        String full = url + "?action=notif&token=" + URLEncoder.encode(token, "UTF-8") + "&since=" + since;
        HttpURLConnection c = (HttpURLConnection) new URL(full).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) return;
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            r.close();
        }
        JSONObject res = new JSONObject(sb.toString());
        if (!res.optBoolean("ok")) return;
        JSONArray items = res.optJSONArray("items");
        if (items == null || items.length() == 0) return;

        List<String> seen = new ArrayList<>(Arrays.asList(sp.getString("seen", "").split(",")));
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject n = items.getJSONObject(i);
            String id = n.optString("id");
            maxAt = Math.max(maxAt, parseIso(n.optString("at")));
            if (id.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            String body = n.optString("body");
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(R.drawable.ic_stat_ssb)
                    .setContentTitle(n.optString("title"))
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            if (nm != null) nm.notify(id.hashCode(), b.build());
        }
        while (seen.size() > 150) seen.remove(0);
        StringBuilder joined = new StringBuilder();
        for (String s : seen) {
            if (s.isEmpty()) continue;
            if (joined.length() > 0) joined.append(',');
            joined.append(s);
        }
        sp.edit().putLong("maxAt", maxAt).putString("seen", joined.toString()).apply();
    }
}
