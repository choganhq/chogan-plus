package io.github.choganhq.choganplus;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.games.GamesSignInClient;
import com.google.android.gms.games.LeaderboardsClient;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.leaderboard.LeaderboardScore;
import com.google.android.gms.games.leaderboard.LeaderboardVariant;
import com.google.android.gms.games.SnapshotsClient;
import com.google.android.gms.games.snapshot.Snapshot;
import com.google.android.gms.games.snapshot.SnapshotMetadataChange;

import java.nio.charset.StandardCharsets;

/**
 * Play Games Services v2 for the page: who is signed in, one cloud save, leaderboards (#3).
 *
 * The SDK initialises itself through the library's PlayGamesInitProvider and signs the
 * player in on its own when the app starts, so this side never shows a login of its own;
 * signIn() is only for a player who declined that first prompt.
 *
 * The page (chogan's www/lib/chogan.js) decides what goes into the save and when; this side
 * moves one UTF-8 string per save name and reports failures as rejected calls, never as
 * crashes: a phone without Play Games must still play every game.
 */
@CapacitorPlugin(name = "ChoganGames")
public class GamesPlugin extends Plugin {
    // A save opened on two phones offline comes back as a conflict on the next sync. The
    // page writes the whole state each time, so the newest copy is the right one.
    private static final int POLICY = SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED;
    // Play's limit for one snapshot's data
    private static final int MAX_BYTES = 3 * 1024 * 1024;

    private static String reason(Exception e) {
        if (e instanceof ApiException) return "status " + ((ApiException) e).getStatusCode();
        return e == null ? "unknown" : String.valueOf(e.getMessage());
    }

    /** {authenticated, name?, id?} without any prompt. */
    @PluginMethod
    public void status(PluginCall call) {
        Activity a = getActivity();
        PlayGames.getGamesSignInClient(a).isAuthenticated().addOnCompleteListener(t -> {
            boolean ok = t.isSuccessful() && t.getResult().isAuthenticated();
            if (!ok) {
                JSObject r = new JSObject();
                r.put("authenticated", false);
                call.resolve(r);
                return;
            }
            player(a, call);
        });
    }

    /** Asks Play Games to sign the player in; resolves like status(). */
    @PluginMethod
    public void signIn(PluginCall call) {
        Activity a = getActivity();
        GamesSignInClient c = PlayGames.getGamesSignInClient(a);
        c.signIn().addOnCompleteListener(t -> {
            if (t.isSuccessful() && t.getResult().isAuthenticated()) player(a, call);
            else {
                JSObject r = new JSObject();
                r.put("authenticated", false);
                call.resolve(r);
            }
        });
    }

    private void player(Activity a, PluginCall call) {
        PlayGames.getPlayersClient(a).getCurrentPlayer().addOnCompleteListener(t -> {
            JSObject r = new JSObject();
            r.put("authenticated", true);
            if (t.isSuccessful() && t.getResult() != null) {
                r.put("name", t.getResult().getDisplayName());
                r.put("id", t.getResult().getPlayerId());
            }
            call.resolve(r);
        });
    }

    /** {name} → {data: string|null, modified: ms since epoch, 0 when the save is new}. */
    @PluginMethod
    public void load(PluginCall call) {
        String name = call.getString("name");
        if (name == null || name.isEmpty()) { call.reject("name is required"); return; }
        SnapshotsClient sc = PlayGames.getSnapshotsClient(getActivity());
        sc.open(name, true, POLICY).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) { call.reject("open failed: " + reason(t.getException())); return; }
            SnapshotsClient.DataOrConflict<Snapshot> r = t.getResult();
            if (r.isConflict()) { call.reject("conflict"); return; }
            Snapshot s = r.getData();
            JSObject out = new JSObject();
            try {
                byte[] b = s.getSnapshotContents().readFully();
                out.put("data", b == null || b.length == 0 ? null : new String(b, StandardCharsets.UTF_8));
                out.put("modified", b == null || b.length == 0 ? 0 : s.getMetadata().getLastModifiedTimestamp());
            } catch (Exception e) {
                call.reject("read failed: " + reason(e));
                sc.discardAndClose(s);
                return;
            }
            // Reading never changes the save, and an open snapshot blocks the next open
            sc.discardAndClose(s);
            call.resolve(out);
        });
    }

    /** {name, data, description?} → {modified}. Offline, Play Games keeps it and syncs later. */
    @PluginMethod
    public void save(PluginCall call) {
        String name = call.getString("name");
        String data = call.getString("data");
        if (name == null || name.isEmpty() || data == null) { call.reject("name and data are required"); return; }
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) { call.reject("too large: " + bytes.length + " bytes"); return; }
        String desc = call.getString("description", "");
        SnapshotsClient sc = PlayGames.getSnapshotsClient(getActivity());
        sc.open(name, true, POLICY).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) { call.reject("open failed: " + reason(t.getException())); return; }
            SnapshotsClient.DataOrConflict<Snapshot> r = t.getResult();
            if (r.isConflict()) { call.reject("conflict"); return; }
            Snapshot s = r.getData();
            if (!s.getSnapshotContents().writeBytes(bytes)) {
                sc.discardAndClose(s);
                call.reject("write failed");
                return;
            }
            SnapshotMetadataChange change = new SnapshotMetadataChange.Builder().setDescription(desc).build();
            sc.commitAndClose(s, change).addOnCompleteListener(c -> {
                if (!c.isSuccessful()) { call.reject("commit failed: " + reason(c.getException())); return; }
                JSObject out = new JSObject();
                out.put("modified", c.getResult().getLastModifiedTimestamp());
                call.resolve(out);
            });
        });
    }

    /** {leaderboard, score}: the leaderboard id from Play Console, a whole-number score. */
    @PluginMethod
    public void submitScore(PluginCall call) {
        String id = call.getString("leaderboard", leagueId());
        Long score = call.getLong("score");
        if (id == null || score == null) { call.reject("leaderboard and score are required"); return; }
        PlayGames.getLeaderboardsClient(getActivity()).submitScoreImmediate(id, score).addOnCompleteListener(t -> {
            if (t.isSuccessful()) call.resolve();
            else call.reject("submit failed: " + reason(t.getException()));
        });
    }

    /** {leaderboard?}: one leaderboard, or the list of all when no id is given. */
    @PluginMethod
    public void showLeaderboard(PluginCall call) {
        Activity a = getActivity();
        String id = call.getString("leaderboard", leagueId());
        com.google.android.gms.tasks.Task<Intent> t = id == null
            ? PlayGames.getLeaderboardsClient(a).getAllLeaderboardsIntent()
            : PlayGames.getLeaderboardsClient(a).getLeaderboardIntent(id);
        t.addOnCompleteListener(i -> {
            if (!i.isSuccessful()) { call.reject("leaderboard failed: " + reason(i.getException())); return; }
            // Play's own screen; nothing comes back that the page needs
            a.startActivityForResult(i.getResult(), 9004);
            call.resolve();
        });
    }

    /**
     * {max?} → {rows: [{rank, name, score, me}], me: {rank, name, score}|null}: this week's
     * top of the league leaderboard and the player's own entry, for the table the page draws
     * itself (#20). The week is Play's, which resets at midnight Pacific time between Saturday
     * and Sunday, not at the local league's reset.
     */
    @PluginMethod
    public void loadScores(PluginCall call) {
        Activity a = getActivity();
        String id = leagueId();
        if (id == null) { call.reject("no leaderboard"); return; }
        int max = Math.max(1, Math.min(25, call.getInt("max", 10)));
        LeaderboardsClient lc = PlayGames.getLeaderboardsClient(a);
        PlayGames.getPlayersClient(a).getCurrentPlayerId().addOnCompleteListener(p -> {
            String self = p.isSuccessful() ? p.getResult() : null;
            lc.loadTopScores(id, LeaderboardVariant.TIME_SPAN_WEEKLY, LeaderboardVariant.COLLECTION_PUBLIC, max)
              .addOnCompleteListener(t -> {
                if (!t.isSuccessful() || t.getResult() == null || t.getResult().get() == null) {
                    call.reject("load failed: " + reason(t.getException()));
                    return;
                }
                LeaderboardsClient.LeaderboardScores ls = t.getResult().get();
                JSArray rows = new JSArray();
                try {
                    for (LeaderboardScore s : ls.getScores()) {
                        JSObject r = entry(s);
                        if (r == null) continue;
                        r.put("me", self != null && s.getScoreHolder() != null && self.equals(s.getScoreHolder().getPlayerId()));
                        rows.put(r);
                    }
                } finally {
                    // The buffer holds native data until released (LeaderboardScores docs)
                    ls.release();
                }
                JSObject out = new JSObject();
                out.put("rows", rows);
                lc.loadCurrentPlayerLeaderboardScore(id, LeaderboardVariant.TIME_SPAN_WEEKLY, LeaderboardVariant.COLLECTION_PUBLIC)
                  .addOnCompleteListener(m -> {
                    // No score this week yet is a normal answer, not a failure of the table
                    JSObject me = m.isSuccessful() && m.getResult() != null ? entry(m.getResult().get()) : null;
                    out.put("me", me == null ? JSObject.NULL : me);
                    call.resolve(out);
                });
            });
        });
    }

    private static JSObject entry(LeaderboardScore s) {
        if (s == null || s.getRank() < 1) return null;
        JSObject r = new JSObject();
        r.put("rank", s.getRank());
        r.put("name", s.getScoreHolderDisplayName());
        r.put("score", s.getRawScore());
        return r;
    }

    // chogan#707: the page sends league points without an id, so www/ never names a Play
    // resource; the id lives here with the rest of the Play Games config. Looked up by name
    // so a build without the leaderboard still compiles and simply rejects the submit.
    private String leagueId() {
        Context c = getContext();
        int res = c.getResources().getIdentifier("leaderboard_league", "string", c.getPackageName());
        if (res == 0) return null;
        String id = c.getString(res);
        return id.isEmpty() ? null : id;
    }
}
