package com.mscope.browser.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.mscope.browser.core.TextKit;

import java.io.File;

/**
 * 工具需要「界面权限」的那些动作的宿主实现。
 *
 * <p>为什么要有这层：工具里的大部分逻辑应该是纯逻辑（能单测、能脱离 Activity），
 * 但通知、剪贴板、分享、打开浏览器、预览页这些必须碰 {@link Context} 甚至 Activity。
 * 把这类动作收敛到一个接口，{@link AgentTools} 就只依赖接口，测试里给个桩实现即可。
 */
public interface ToolHost {

    /** 在应用内预览工作区 HTML。 */
    void openPreview(String relPath);

    /** 发一条通知；返回是否成功（用户关了通知权限就是 false）。 */
    boolean notify(String title, String text);

    /** 写剪贴板。 */
    boolean setClipboard(String text);

    /** 读剪贴板；没有内容返回空串。 */
    String getClipboard();

    /** 用系统浏览器打开链接。 */
    boolean openUrl(String url);

    /** 共享一个文件。 */
    boolean shareFile(File f, String mime);

    /**
     * 默认实现：给 {@link AgentTools} 在没有界面时用（例如后台导出）。
     * 所有动作都返回 false，绝不崩 —— 工具结果里说「不可用」比抛异常好。
     */
    ToolHost NOOP = new ToolHost() {
        @Override
        public void openPreview(String relPath) {
        }

        @Override
        public boolean notify(String title, String text) {
            return false;
        }

        @Override
        public boolean setClipboard(String text) {
            return false;
        }

        @Override
        public String getClipboard() {
            return "";
        }

        @Override
        public boolean openUrl(String url) {
            return false;
        }

        @Override
        public boolean shareFile(File f, String mime) {
            return false;
        }
    };

    /** 基于 {@link Context} 的通用实现。 */
    final class FromContext implements ToolHost {
        private final Context ctx;
        private final PreviewSink preview;

        public interface PreviewSink {
            void open(String relPath);
        }

        public FromContext(Context ctx, PreviewSink preview) {
            this.ctx = ctx.getApplicationContext();
            this.preview = preview;
        }

        @Override
        public void openPreview(String relPath) {
            if (preview != null) preview.open(relPath);
        }

        @Override
        public boolean notify(String title, String text) {
            return Notifications.post(ctx, title, text);
        }

        @Override
        public boolean setClipboard(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return false;
                cm.setPrimaryClip(ClipData.newPlainText("text", TextKit.trimUtf8(text, 64 * 1024)));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public String getClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip.getItemCount() == 0) return "";
                CharSequence cs = clip.getItemAt(0).coerceToText(ctx);
                return cs == null ? "" : cs.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public boolean openUrl(String url) {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean shareFile(File f, String mime) {
            try {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, ctx.getPackageName() + ".files", f);
                Intent it = new Intent(Intent.ACTION_SEND);
                it.setType(mime == null ? "*/*" : mime);
                it.putExtra(Intent.EXTRA_STREAM, uri);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(it, f.getName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
