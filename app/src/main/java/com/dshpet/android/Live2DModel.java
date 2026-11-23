package com.dshpet.android;

import com.live2d.sdk.cubism.framework.CubismDefaultParameterId;
import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.effect.CubismLook;
import com.live2d.sdk.cubism.framework.id.CubismId;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.motion.ACubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismLookUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismPoseUpdater;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 一个 Cubism 模型的加载 / 更新 / 绘制。
 * 这里只做离屏渲染需要的部分：moc3、纹理、表情、动作、pose、physics、look。
 * 所有资源都从 S-G/live2d/<id>/ 目录读，纹理直接怼进当前 GL 上下文。
 */
final class Live2DModel extends CubismUserModel {
    static final int PRIORITY_NONE = 0;
    static final int PRIORITY_IDLE = 1;
    static final int PRIORITY_NORMAL = 2;
    static final int PRIORITY_FORCE = 3;

    private static final String GROUP_IDLE = "Idle";
    private static final String[] GROUP_TAP = {"TapBody", "TapHead", "Flick", "FlickHead", "Tap"};

    private final Random rnd = new Random();
    private final CubismId idAngleX;
    private final CubismId idAngleY;
    private final CubismId idAngleZ;
    private final CubismId idBodyAngleX;
    private final CubismId idEyeBallX;
    private final CubismId idEyeBallY;
    private final Map<String, ACubismMotion> motions = new HashMap<>();
    private final Map<String, ACubismMotion> expressions = new HashMap<>();
    private ICubismModelSetting setting;
    private String home = "";
    private float userTime;
    private float canvasRatio = 1.5f;
    private int idleIndex;

    Live2DModel() {
        CubismIdManager ids = null;
        try {
            ids = com.live2d.sdk.cubism.framework.CubismFramework.getIdManager();
        } catch (Throwable ignored) {
        }
        idAngleX = id(ids, CubismDefaultParameterId.ParameterId.ANGLE_X.getId());
        idAngleY = id(ids, CubismDefaultParameterId.ParameterId.ANGLE_Y.getId());
        idAngleZ = id(ids, CubismDefaultParameterId.ParameterId.ANGLE_Z.getId());
        idBodyAngleX = id(ids, CubismDefaultParameterId.ParameterId.BODY_ANGLE_X.getId());
        idEyeBallX = id(ids, CubismDefaultParameterId.ParameterId.EYE_BALL_X.getId());
        idEyeBallY = id(ids, CubismDefaultParameterId.ParameterId.EYE_BALL_Y.getId());
    }

    private static CubismId id(CubismIdManager mgr, String name) {
        try {
            return mgr == null ? null : mgr.getId(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取并初始化模型。失败直接抛异常，由渲染线程降级处理。
     *
     * @param dir       模型目录
     * @param model3Rel model3.json 相对 dir 的路径
     */
    void load(File dir, String model3Rel, int viewW, int viewH) {
        home = dir.getAbsolutePath() + File.separator;
        byte[] json = Live2DRenderer.readFile(new File(dir, model3Rel));
        if (json == null || json.length == 0) throw new IllegalStateException("model3.json 读不到");
        setting = new CubismModelSettingJson(json);
        if (setting.getJson() == null) throw new IllegalStateException("model3.json 解析失败");

        // moc3
        String moc = setting.getModelFileName();
        if (moc == null || moc.isEmpty()) throw new IllegalStateException("model3.json 里没有 Moc");
        byte[] mocBytes = Live2DRenderer.readFile(new File(home + moc));
        if (mocBytes == null || mocBytes.length == 0) throw new IllegalStateException("moc3 读不到");
        loadModel(mocBytes, false);
        if (model == null) throw new IllegalStateException("moc3 加载失败");

        // 渲染器 + 纹理
        CubismRenderer renderer = CubismRendererAndroid.create(viewW, viewH);
        setupRenderer(renderer);
        setupTextures();

        // 表情
        int expCount = setting.getExpressionCount();
        for (int i = 0; i < expCount; i++) {
            String name = setting.getExpressionName(i);
            String path = setting.getExpressionFileName(i);
            if (name == null || path == null || path.isEmpty()) continue;
            byte[] buf = Live2DRenderer.readFile(new File(home + path));
            if (buf == null || buf.length == 0) continue;
            CubismExpressionMotion motion = loadExpression(buf);
            if (motion != null) expressions.put(name, motion);
        }
        if (!expressions.isEmpty()) {
            updateScheduler.addUpdatableList(new CubismExpressionUpdater(expressionManager));
        }

        // pose
        String posePath = setting.getPoseFileName();
        if (posePath != null && !posePath.isEmpty()) {
            byte[] buf = Live2DRenderer.readFile(new File(home + posePath));
            if (buf != null && buf.length > 0) loadPose(buf);
        }
        if (pose != null) updateScheduler.addUpdatableList(new CubismPoseUpdater(pose));

        // physics
        String physicsPath = setting.getPhysicsFileName();
        if (physicsPath != null && !physicsPath.isEmpty()) {
            byte[] buf = Live2DRenderer.readFile(new File(home + physicsPath));
            if (buf != null && buf.length > 0) loadPhysics(buf);
        }
        if (physics != null) updateScheduler.addUpdatableList(new CubismPhysicsUpdater(physics));

        // userdata
        String userData = setting.getUserDataFile();
        if (userData != null && !userData.isEmpty()) {
            byte[] buf = Live2DRenderer.readFile(new File(home + userData));
            if (buf != null && buf.length > 0) loadUserData(buf);
        }

        // 视线跟随
        look = CubismLook.create();
        List<CubismLook.LookParameterData> params = new ArrayList<>();
        addLook(params, idAngleX, 20f);
        addLook(params, idAngleY, 0f, 20f);
        addLook(params, idAngleZ, 0f, 0f, -20f);
        addLook(params, idBodyAngleX, 8f);
        addLook(params, idEyeBallX, 1f);
        addLook(params, idEyeBallY, 0f, 1f);
        look.setParameters(params);
        updateScheduler.addUpdatableList(new CubismLookUpdater(look, dragManager));
        updateScheduler.sortUpdatableList();

        // 布局
        Map<String, Float> layout = new HashMap<>();
        if (setting.getLayoutMap(layout) && modelMatrix != null) modelMatrix.setupFromLayout(layout);

        model.saveParameters();
        canvasRatio = model.getCanvasHeight() / (float) model.getCanvasWidth();

        // 预读动作
        for (int i = 0; i < setting.getMotionGroupCount(); i++) {
            preLoadMotionGroup(setting.getMotionGroupName(i));
        }
        motionManager.stopAllMotions();
    }

    private void addLook(List<CubismLook.LookParameterData> list, CubismId id, float scale) {
        if (id != null) list.add(new CubismLook.LookParameterData(id, scale));
    }

    private void addLook(List<CubismLook.LookParameterData> list, CubismId id, float min, float max) {
        if (id != null) list.add(new CubismLook.LookParameterData(id, min, max));
    }

    private void addLook(List<CubismLook.LookParameterData> list, CubismId id, float def, float min, float max) {
        if (id != null) list.add(new CubismLook.LookParameterData(id, def, min, max));
    }

    private void setupTextures() {
        int count = setting.getTextureCount();
        CubismRendererAndroid r = renderer();
        if (r == null) return;
        for (int i = 0; i < count; i++) {
            String name = setting.getTextureFileName(i);
            if (name == null || name.isEmpty()) continue;
            int tex = Live2DRenderer.loadTexture(home + name);
            if (tex == 0) continue;
            r.bindTexture(i, tex);
            r.isPremultipliedAlpha(true);
        }
    }

    private void preLoadMotionGroup(String group) {
        int count = setting.getMotionCount(group);
        for (int i = 0; i < count; i++) {
            String path = setting.getMotionFileName(group, i);
            if (path == null || path.isEmpty()) continue;
            byte[] buf = Live2DRenderer.readFile(new File(home + path));
            if (buf == null || buf.length == 0) continue;
            CubismMotion motion;
            try {
                motion = loadMotion(buf);
            } catch (Throwable t) {
                continue;
            }
            if (motion == null) continue;
            float in = setting.getMotionFadeInTimeValue(group, i);
            if (in != -1f) motion.setFadeInTime(in);
            float out = setting.getMotionFadeOutTimeValue(group, i);
            if (out != -1f) motion.setFadeOutTime(out);
            motions.put(group + "_" + i, motion);
        }
    }

    void update(float delta) {
        if (model == null) return;
        isUpdated(false);
        userTime += delta;
        model.loadParameters();
        if (motionManager.isFinished()) {
            startIdleMotion(PRIORITY_IDLE);
        } else {
            motionManager.updateMotion(model, delta);
        }
        model.saveParameters();
        updateScheduler.onLateUpdate(model, delta);
        model.update();
        isUpdated(true);
    }

    /** 按视口尺寸等比铺满，多余方向留透明。y 轴翻转，让 glReadPixels 读回来是正的。 */
    void draw(CubismMatrix44 projection, int viewW, int viewH) {
        if (model == null || modelMatrix == null) return;
        CubismRendererAndroid r = renderer();
        if (r == null) return;
        float aspect = viewW / (float) viewH;
        float display = viewH / (float) viewW;
        projection.loadIdentity();
        if (canvasRatio < display) {
            modelMatrix.setWidth(2.0f);
            projection.scale(1.0f, aspect);
        } else {
            modelMatrix.setHeight(2.0f);
            projection.scale(1.0f / aspect, 1.0f);
        }
        projection.scale(1.0f, -1.0f);
        CubismMatrix44.multiply(modelMatrix.getArray(), projection.getArray(), projection.getArray());
        r.setMvpMatrix(projection);
        r.drawModel();
    }

    float canvasRatio() {
        return canvasRatio;
    }

    boolean isEmpty() {
        return model == null;
    }

    int motionCount() {
        return motions.size();
    }

    /* ---------------- 动作 ---------------- */

    private boolean hasGroup(String group) {
        for (String k : motions.keySet()) {
            if (k.startsWith(group + "_")) return true;
        }
        return false;
    }

    private int countGroup(String group) {
        int n = 0;
        while (motions.containsKey(group + "_" + n)) n++;
        return n;
    }

    private int start(String group, int index, int priority) {
        if (motions.isEmpty()) return -1;
        ACubismMotion motion = motions.get(group + "_" + index);
        if (motion == null) return -1;
        try {
            if (priority == PRIORITY_FORCE) {
                motionManager.setReservationPriority(priority);
            } else if (!motionManager.reserveMotion(priority)) {
                return -1;
            }
            return motionManager.startMotionPriority(motion, priority);
        } catch (Throwable t) {
            return -1;
        }
    }

    void startIdleMotion(int priority) {
        int n = countGroup(GROUP_IDLE);
        if (n <= 0) return;
        if (priority != PRIORITY_FORCE) start(GROUP_IDLE, idleIndex % n, priority);
        else start(GROUP_IDLE, rnd.nextInt(n), priority);
    }

    void idle() {
        int n = countGroup(GROUP_IDLE);
        if (n <= 0) return;
        // 正在播动作时不要重开，否则同一段待机动作会被打断从头再放一遍
        if (!motionManager.isFinished()) return;
        idleIndex = (idleIndex + 1) % n;
        start(GROUP_IDLE, idleIndex, PRIORITY_IDLE);
    }

    /** 点击/互动：优先找 TapBody、TapHead 之类的互动动作，找不到就随便挑一个非 Idle 动作。 */
    void tap() {
        for (String g : GROUP_TAP) {
            if (hasGroup(g)) {
                int n = countGroup(g);
                if (start(g, rnd.nextInt(n), PRIORITY_NORMAL) >= 0) return;
            }
        }
        List<String> groups = new ArrayList<>();
        for (String k : motions.keySet()) {
            String g = k.substring(0, k.lastIndexOf('_'));
            if (!GROUP_IDLE.equals(g) && !groups.contains(g)) groups.add(g);
        }
        if (groups.isEmpty()) {
            startIdleMotion(PRIORITY_NORMAL);
            return;
        }
        String g = groups.get(rnd.nextInt(groups.size()));
        int n = countGroup(g);
        if (n > 0) start(g, rnd.nextInt(n), PRIORITY_NORMAL);
    }

    /** 按 Pet 的动作名挑一动作；识别不出来就当成点击。 */
    void playByName(String name) {
        String s = name == null ? "" : name;
        String key = null;
        if (contains(s, "睡", "困", "休息", "躺")) key = "Sleep";
        else if (contains(s, "吃", "喝")) key = "TapBody";
        else if (contains(s, "跳", "舞", "庆祝", "高兴", "开心")) key = "TapBody";
        if (key != null && hasGroup(key)) {
            int n = countGroup(key);
            if (start(key, rnd.nextInt(n), PRIORITY_NORMAL) >= 0) return;
        }
        tap();
    }

    private static boolean contains(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    void expression(int index) {
        if (expressions.isEmpty()) return;
        List<ACubismMotion> list = new ArrayList<>(expressions.values());
        ACubismMotion m = list.get(Math.abs(index) % list.size());
        try {
            expressionManager.startMotionPriority(m, PRIORITY_NORMAL);
        } catch (Throwable ignored) {
        }
    }

    void look(float x, float y) {
        try {
            dragManager.set(x, y);
        } catch (Throwable ignored) {
        }
    }

    void close() {
        try {
            delete();
        } catch (Throwable ignored) {
        }
    }

    private CubismRendererAndroid renderer() {
        try {
            CubismRenderer r = this.<CubismRenderer>getRenderer();
            return r instanceof CubismRendererAndroid ? (CubismRendererAndroid) r : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
