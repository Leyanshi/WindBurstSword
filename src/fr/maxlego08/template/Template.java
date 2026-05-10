package fr.maxlego08.template;

import fr.maxlego08.template.listener.SwordSmashListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WindBurstSword 插件主类。
 *
 * <p>本次精简后仅保留基础插件入口（main class）并注册核心监听器，
 * 移除了模板工程中与本需求无关的命令系统、占位符系统、GUI、存储等功能。</p>
 */
public final class Template extends JavaPlugin {

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(new SwordSmashListener(this), this);
        getLogger().info("WindBurstSword 已启用（Paper 1.21）");
    }

    @Override
    public void onDisable() {
        getLogger().info("WindBurstSword 已关闭");
    }
}
