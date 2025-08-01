// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.theelectricfactory.focus

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkFeature
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.runtime.SceneAudioAsset
import com.meta.spatial.runtime.SceneAudioPlayer
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.lang.ref.WeakReference
import com.meta.spatial.compose.ComposeFeature
import com.meta.theelectricfactory.focus.data.Project
import com.meta.theelectricfactory.focus.data.StickyColor
import com.meta.theelectricfactory.focus.data.environments
import com.meta.theelectricfactory.focus.data.skyboxes
import com.meta.theelectricfactory.focus.managers.DatabaseManager
import com.meta.theelectricfactory.focus.managers.AIManager
import com.meta.theelectricfactory.focus.managers.PanelManager
import com.meta.theelectricfactory.focus.managers.TasksManager
import com.meta.theelectricfactory.focus.managers.ToolManager
import com.meta.theelectricfactory.focus.systems.BoardParentingSystem
import com.meta.theelectricfactory.focus.systems.DatabaseUpdateSystem
import com.meta.theelectricfactory.focus.systems.GeneralSystem
import com.meta.theelectricfactory.focus.systems.UpdateTimeSystem
import com.meta.theelectricfactory.focus.tools.StickyNote
import com.meta.theelectricfactory.focus.tools.Tool
import com.meta.theelectricfactory.focus.tools.WebView
import com.meta.theelectricfactory.focus.viewmodels.FocusViewModel
import com.meta.theelectricfactory.focus.utils.addOnSelectListener
import com.meta.theelectricfactory.focus.utils.deleteObject
import com.meta.theelectricfactory.focus.utils.getChildren
import com.meta.theelectricfactory.focus.utils.getNewUUID
import com.meta.theelectricfactory.focus.utils.placeInFront

class ImmersiveActivity : AppSystemActivity() {

    lateinit var DB: DatabaseManager
    var appStarted: Boolean = false

    // PROJECT ELEMENTS
    lateinit var logo: Entity
    lateinit var environment: Entity
    lateinit var skybox: Entity
    lateinit var clock: Entity
    lateinit var speaker: Entity
    lateinit var speakerState: Entity
    lateinit var deleteButton: Entity

    // Sounds
    lateinit var ambientSound: SceneAudioAsset
    lateinit var createSound: SceneAudioAsset
    lateinit var deleteSound: SceneAudioAsset
    lateinit var timerSound: SceneAudioAsset
    lateinit var ambientSoundPlayer: SceneAudioPlayer

    // VARIABLES
    var templateState = 0
    var templatePriority = 0
    var currentEnvironment = 0

    var currentProject: Project? = null
    var currentObjectSelected: Entity? = null
    var passthroughEnabled: Boolean = false
    var speakerIsOn = false

    override fun registerFeatures(): List<SpatialFeature> {
        val features =
            mutableListOf<SpatialFeature>(VRFeature(this), ComposeFeature(), IsdkFeature(this, spatial, systemManager))
        return features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("Focus", "Focus> onCreate")

        instance = WeakReference(this)
        PanelManager.instance.immA = this
        TasksManager.instance.immA = this

        // Register custom systems and components
        componentManager.registerComponent<UniqueAssetComponent>(UniqueAssetComponent.Companion)
        componentManager.registerComponent<ToolComponent>(ToolComponent.Companion)
        componentManager.registerComponent<TimeComponent>(TimeComponent.Companion)
        componentManager.registerComponent<AttachableComponent>(AttachableComponent.Companion)

        systemManager.registerSystem(DatabaseUpdateSystem())
        systemManager.registerSystem(UpdateTimeSystem())
        systemManager.registerSystem(GeneralSystem())
        systemManager.registerSystem(BoardParentingSystem())

        ambientSound = SceneAudioAsset.loadLocalFile("audio/ambient.wav")
        createSound = SceneAudioAsset.loadLocalFile("audio/create.wav")
        deleteSound = SceneAudioAsset.loadLocalFile("audio/delete.wav")
        timerSound = SceneAudioAsset.loadLocalFile("audio/timer.wav")
        ambientSoundPlayer = SceneAudioPlayer(scene, ambientSound)
    }

    override fun onSceneReady() {
        super.onSceneReady()
        Log.i("Focus", "Focus> onSceneReady")

        // Locomotion system disabled for a better interaction between controllers and panels
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        // Create or load local database
        DB = DatabaseManager(this)

        // Main panels created
        PanelManager.instance.createPanels()
        // Rest of the elements in scene are created
        createSceneElements()
        // Initial state of objects in scene
        setInitialState()
    }

    override fun registerPanels(): List<PanelRegistration> {
         return PanelManager.instance.registerFocusPanels()
    }

    override fun onPause() {
        super.onPause()
        // Stop audio when app is on pause
        if (currentProject != null && speakerIsOn) ambientSoundPlayer.stop()
    }

    override fun onResume() {
        super.onResume()
        // Resume audio when app return from pause
        if (currentProject != null && speakerIsOn) playAmbientSound()
    }

    private fun setInitialState() {
        // Set initial state of objects in scene
        setPassthrough(true)
        setLighting(-1)

        if (appStarted) {
            environment.setComponent(Visible(false))
            skybox.setComponent(Visible(false))
        }

        deleteButton.setComponent(Visible(false))

        if (!PanelManager.instance.homePanel.getComponent<Visible>().isVisible) placeInFront(PanelManager.instance.homePanel)
        PanelManager.instance.homePanel.setComponent(Visible(appStarted))
        PanelManager.instance.toolbarPanel.setComponent(Visible(false))
        PanelManager.instance.tasksPanel.setComponent(Visible(false))
        PanelManager.instance.aiExchangePanel.setComponent(Visible(false))
        PanelManager.instance.closeSubPanels()

        showClock(false)
        showSpeaker(false)

        ToolManager.instance.cleanElements()
    }

    fun newProject() {
        // New project variables reset
        templateState = 0
        templatePriority = 0
        currentEnvironment = 0

        currentProject = null
        currentObjectSelected = null
        passthroughEnabled = false
        speakerIsOn = false
        AIManager.instance.lastAIResponse = ""
        AIManager.instance.waitingForAI = false
        setInitialState()
    }

    // Load project from scroll view in First Fragment
    @SuppressLint("Range")
    fun loadProject(id: Int) {
        PanelManager.instance.homePanel.setComponent(Visible(false))

        if (currentProject?.uuid == id) return

        // Clean elements from previous projects
        ToolManager.instance.cleanElements()

        // Load project settings
        val cursor = DB.getProject(id)
        if (cursor.moveToFirst()) {
            val projectName = cursor.getString(cursor.getColumnIndex(DatabaseManager.PROJECT_NAME))
            val mr =
                if (cursor.getInt(cursor.getColumnIndex(DatabaseManager.PROJECT_MR)) == 1) true
                else false
            val env = cursor.getInt(cursor.getColumnIndex(DatabaseManager.PROJECT_ENVIRONMENT))
            currentProject = Project(id, projectName, mr, env)
            FocusViewModel.instance.updateCurrentProjectUuid(id)
        }
        cursor.close()

        if (currentProject?.MR == true) {
            selectMRMode()
        } else {
            selectEnvironment(currentProject?.environment!!)
        }

        // Position Unique Assets
        val uniqueAssetsCursor = DB.getUniqueAssets(currentProject?.uuid)
        if (uniqueAssetsCursor.moveToFirst()) {
            while (!uniqueAssetsCursor.isAfterLast) {
                val uuid =
                    uniqueAssetsCursor.getInt(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_UUID))
                val rawType =
                    uniqueAssetsCursor.getString(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_TYPE))
                val type = AssetType.entries.find { it.name == rawType }
                val state =
                    uniqueAssetsCursor.getInt(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_STATE))

                val posX =
                    uniqueAssetsCursor.getFloat(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_POSITION_X))
                val posY =
                    uniqueAssetsCursor.getFloat(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_POSITION_Y))
                val posZ =
                    uniqueAssetsCursor.getFloat(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_POSITION_Z))

                val rotW =
                    uniqueAssetsCursor.getFloat(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_ROTATION_W))
                val rotX =
                    uniqueAssetsCursor.getFloat(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_ROTATION_X))
                val rotY =
                    uniqueAssetsCursor.getFloat(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_ROTATION_Y))
                val rotZ =
                    uniqueAssetsCursor.getFloat(
                        uniqueAssetsCursor.getColumnIndex(DatabaseManager.UNIQUE_ASSET_ROTATION_Z))

                when (type) {
                    AssetType.TASKS_PANEL -> {
                        PanelManager.instance.tasksPanel.setComponent(UniqueAssetComponent(uuid, AssetType.TASKS_PANEL))
                        PanelManager.instance.tasksPanel.setComponent(Visible(if (state == 1) true else false))
                        PanelManager.instance.tasksPanel.setComponent(
                            Transform(Pose(Vector3(posX, posY, posZ), Quaternion(rotW, rotX, rotY, rotZ))))
                    }
                    AssetType.AI_PANEL -> {
                        PanelManager.instance.aiExchangePanel.setComponent(UniqueAssetComponent(uuid, AssetType.AI_PANEL))
                        PanelManager.instance.aiExchangePanel.setComponent(Visible(if (state == 1) true else false))
                        PanelManager.instance.aiExchangePanel.setComponent(
                            Transform(Pose(Vector3(posX, posY, posZ), Quaternion(rotW, rotX, rotY, rotZ))))
                    }
                    AssetType.CLOCK -> {
                        clock.setComponent(UniqueAssetComponent(uuid, AssetType.CLOCK))
                        clock.setComponent(
                            Transform(Pose(Vector3(posX, posY, posZ), Quaternion(rotW, rotX, rotY, rotZ))))
                    }
                    AssetType.SPEAKER -> {
                        speaker.setComponent(UniqueAssetComponent(uuid, AssetType.SPEAKER))
                        speaker.setComponent(
                            Transform(Pose(Vector3(posX, posY, posZ), Quaternion(rotW, rotX, rotY, rotZ))))
                        speakerIsOn = if (state == 1) true else false
                        if (speakerIsOn) playAmbientSound()
                        else stopAmbientSound()
                    }
                    else -> {
                        Log.e("Focus", "Focus> Unknown Unique Asset")
                    }
                }
                uniqueAssetsCursor.moveToNext()
            }
        }

        placeInFront(PanelManager.instance.toolbarPanel)
        PanelManager.instance.toolbarPanel.setComponent(Visible(true))

        showClock(true)
        showSpeaker(true)

        // Load and create Tool Assets
        val toolsCursor = DB.getToolAssets(currentProject?.uuid)
        if (toolsCursor.moveToFirst()) {
            while (!toolsCursor.isAfterLast) {
                val uuid = toolsCursor.getInt(toolsCursor.getColumnIndex(DatabaseManager.TOOL_UUID))
                val rawType = toolsCursor.getString(toolsCursor.getColumnIndex(DatabaseManager.TOOL_TYPE))
                val type = AssetType.entries.find { it.name == rawType }
                val source = toolsCursor.getString(toolsCursor.getColumnIndex(DatabaseManager.TOOL_SOURCE))
                val size = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_SIZE))
                val deleteHeight = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_DELETE_HEIGHT))
                val parentUuid = toolsCursor.getInt(toolsCursor.getColumnIndex(DatabaseManager.TOOL_PARENT))

                val posX = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_POSITION_X))
                val posY = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_POSITION_Y))
                val posZ = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_POSITION_Z))

                val rotW = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_ROTATION_W))
                val rotX = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_ROTATION_X))
                val rotY = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_ROTATION_Y))
                val rotZ = toolsCursor.getFloat(toolsCursor.getColumnIndex(DatabaseManager.TOOL_ROTATION_Z))

                if (type == AssetType.WEB_VIEW) {
                      WebView(
                          source,
                          uuid,
                          Pose(Vector3(posX, posY, posZ), Quaternion(rotW, rotX, rotY, rotZ)))
                } else {
                    Tool(
                        type = type,
                        source = source,
                        size = size,
                        uuid = uuid,
                        pose = Pose(Vector3(posX, posY, posZ), Quaternion(rotW, rotX, rotY, rotZ)),
                        deleteButtonHeight = deleteHeight,
                        parentUuid = parentUuid
                    )
                }
                toolsCursor.moveToNext()
            }
        }
        toolsCursor.close()

        // Load and create Stickies
        val stickiesCursor = DB.getStickies(currentProject?.uuid)
        if (stickiesCursor.moveToFirst()) {
            while (!stickiesCursor.isAfterLast) {
                val uuid = stickiesCursor.getInt(stickiesCursor.getColumnIndex(DatabaseManager.STICKY_UUID))
                val message =
                    stickiesCursor.getString(stickiesCursor.getColumnIndex(DatabaseManager.STICKY_MESSAGE))
                val rawColor =
                    stickiesCursor.getString(stickiesCursor.getColumnIndex(DatabaseManager.STICKY_COLOR))
                val color = StickyColor.entries.find { it.name == rawColor }
                val parentUuid = stickiesCursor.getInt(stickiesCursor.getColumnIndex(DatabaseManager.STICKY_PARENT))

                val posX =
                    stickiesCursor.getFloat(
                        stickiesCursor.getColumnIndex(DatabaseManager.STICKY_POSITION_X))
                val posY =
                    stickiesCursor.getFloat(
                        stickiesCursor.getColumnIndex(DatabaseManager.STICKY_POSITION_Y))
                val posZ =
                    stickiesCursor.getFloat(
                        stickiesCursor.getColumnIndex(DatabaseManager.STICKY_POSITION_Z))

                val rotW =
                    stickiesCursor.getFloat(
                        stickiesCursor.getColumnIndex(DatabaseManager.STICKY_ROTATION_W))
                val rotX =
                    stickiesCursor.getFloat(
                        stickiesCursor.getColumnIndex(DatabaseManager.STICKY_ROTATION_X))
                val rotY =
                    stickiesCursor.getFloat(
                        stickiesCursor.getColumnIndex(DatabaseManager.STICKY_ROTATION_Y))
                val rotZ =
                    stickiesCursor.getFloat(
                        stickiesCursor.getColumnIndex(DatabaseManager.STICKY_ROTATION_Z))

                StickyNote(
                    uuid,
                    message,
                    color!!,
                    Pose(Vector3(posX, posY, posZ), Quaternion(rotW, rotX, rotY, rotZ)),
                    parentUuid
                )

                stickiesCursor.moveToNext()
            }
        }
        stickiesCursor.close()

        ToolManager.instance.linkToolsWithParentBoards()
    }

    // Save project settings in second fragment
    fun saveProjectSettings(MRMode: Boolean? = null, projectName: String) {
        var mrMode = MRMode != null && MRMode == true

        // Settings if it is a new project...
        if (currentProject == null) {
            Log.i("Focus", "Focus> New project created")

            val project = Project(getNewUUID(), projectName, mrMode, currentEnvironment)
            currentProject = project
            DB.createProject(project)
            FocusViewModel.instance.updateCurrentProjectUuid(project.uuid)

            placeInFront(PanelManager.instance.toolbarPanel)
            PanelManager.instance.toolbarPanel.setComponent(Visible(true))
            PanelManager.instance.tasksPanel.setComponent(Visible(true))
            if (AIManager.instance.AIenabled) PanelManager.instance.aiExchangePanel.setComponent(Visible(true))
            showClock(true)
            showSpeaker(true)

            // Unique elements created to store in database
            val tasksPanelUUID = getNewUUID()
            val aiPanelUUID = getNewUUID()
            val clockUUID = getNewUUID()
            val speakerUUID = getNewUUID()

            PanelManager.instance.tasksPanel.setComponent(UniqueAssetComponent(tasksPanelUUID, AssetType.TASKS_PANEL))
            PanelManager.instance.aiExchangePanel.setComponent(UniqueAssetComponent(aiPanelUUID, AssetType.AI_PANEL))
            clock.setComponent(UniqueAssetComponent(clockUUID, AssetType.CLOCK))
            speaker.setComponent(UniqueAssetComponent(speakerUUID, AssetType.SPEAKER))

            // Initial configuration of panels for a new project
            placeInFront(PanelManager.instance.toolbarPanel)
            placeInFront(PanelManager.instance.tasksPanel, Vector3(-0.45f, -0.04f, 0.8f))
            placeInFront(PanelManager.instance.aiExchangePanel, Vector3(0.45f, -0.05f, 0.8f))
            placeInFront(clock, Vector3(0f, 0.23f, 0.9f))
            placeInFront(speaker, Vector3(-0.65f, -0.3f, 0.65f))

            // Unique elements created in database
            DB.createUniqueAsset(
                tasksPanelUUID,
                project.uuid,
                AssetType.TASKS_PANEL,
                true,
                PanelManager.instance.tasksPanel.getComponent<Transform>().transform
            )

            DB.createUniqueAsset(
                aiPanelUUID,
                project.uuid,
                AssetType.AI_PANEL,
                true,
                PanelManager.instance.aiExchangePanel.getComponent<Transform>().transform
            )

            DB.createUniqueAsset(
                clockUUID,
                project.uuid,
                AssetType.CLOCK,
                true,
                clock.getComponent<Transform>().transform
            )

            DB.createUniqueAsset(
                speakerUUID,
                project.uuid,
                AssetType.SPEAKER,
                true,
                speaker.getComponent<Transform>().transform
            )

            // Initial Web View tool created as an example
            WebView()
            PanelManager.instance.homePanel.setComponent(Visible(false))
            playAmbientSound()

        // if it is not a new project, we only update project settings in database (name, environment)
        } else {

            if (projectName != "") currentProject?.name = projectName
            currentProject?.MR = mrMode
            currentProject?.environment = currentEnvironment

            DB.updateProject(currentProject)
        }
    }

    // Set lighting of scene depending on the chosen 3D environment
    private fun setLighting(env: Int) {

        when (env) {
            -1 -> {
                scene.setLightingEnvironment(
                    Vector3(2.5f, 2.5f, 2.5f), // ambient light color (none in this case)
                    Vector3(1.8f, 1.8f, 1.8f), // directional light color
                    -Vector3(1.0f, 3.0f, 2.0f), // directional light direction
                )
            }
            0 -> {
                scene.setLightingEnvironment(
                    Vector3(1.8f, 1.5f, 1.5f),
                    Vector3(1.5f, 1.5f, 1.5f),
                    -Vector3(1.0f, 3.0f, 2.0f),
                )
            }
            1 -> {
                scene.setLightingEnvironment(
                    Vector3(1.5f, 1.5f, 1.5f),
                    Vector3(1.5f, 1.5f, 1.5f),
                    -Vector3(1.0f, 3.0f, 2.0f),
                )
            }
            2 -> {
                scene.setLightingEnvironment(
                    Vector3(3.5f, 3.5f, 3.5f),
                    Vector3(2f, 2f, 2f),
                    -Vector3(1.0f, 3.0f, 2.0f)
                )
            }
        }
    }

    // Speaker composed object created
    private fun createSpeaker() {

        // Create speaker entity with Mesh component
        speaker =
            Entity.create(
                Mesh(mesh = Uri.parse("speaker.glb")),
                Scale(Vector3(0.08f)),
                Transform(Pose(Vector3(0f))),
                Visible(false),
                Grabbable(true, GrabbableType.PIVOT_Y),
                IsdkGrabbable(billboardOrientation = Vector3(0f, 180f, 0f)),
                // Empty UUID since the asset is not linked with any project for now
                UniqueAssetComponent(type = AssetType.SPEAKER)
            )

        val size = 0.05f
        // Image entity showing state On/Off of the speaker.
        // We make this entity child to the speaker entity, to move them together, as one only object
        speakerState =
            Entity.create(
                // hittable property should be NonCollision if we don't want to interact with it, nor block the parent entity
                Mesh(Uri.parse("mesh://box")).apply { hittable = MeshCollision.NoCollision },
                Box(Vector3(-size, -size, 0f), Vector3(size, size, 0f)),
                Material().apply {
                  baseTextureAndroidResourceId = R.drawable.speaker_on
                  alphaMode = 1
                },
                Transform(Pose(Vector3(0f, -0.01f, 0.045f), Quaternion(-30f, 0f, 0f))),
                // We make this entity child to the speaker entity
                TransformParent(speaker)
            )
    }

    // Clock composed object created by two entities, one with a Mesh component and the other with a Panel component
    private fun createClock() {
        val _width = 0.18f
        val _height = 0.18f
        val _dp = 1100f

        registerPanel(
            PanelRegistration(R.layout.clock_layout) {
                config {
                    themeResourceId = R.style.Theme_Focus_Transparent
                    width = _width
                    height = _height
                    layoutWidthInDp = _dp
                    layoutHeightInDp = _dp * (height / width)
                    includeGlass = false
                }
            }
        )

        // Entity with panel component created
        val clockPanel: Entity =
            Entity.create(
                Panel(R.layout.clock_layout).apply { hittable = MeshCollision.NoCollision },
                Transform(Pose(Vector3(0f, 0f, 0.035f), Quaternion(0f, 180f, 0f))),
                // Adding TimeComponent to clock panel to be able to update it. More info in UpdateTimeSystem.kt
                TimeComponent(AssetType.CLOCK),
            )

        // Creating clock entity with Mesh
        clock =
            Entity.create(
                Mesh(mesh = Uri.parse("clock.glb")),
                Scale(Vector3(0.1f)),
                Transform(Pose(Vector3(0f))),
                Grabbable(true, GrabbableType.FACE),
                IsdkGrabbable(billboardOrientation = Vector3(0f, 180f, 0f)),
                Visible(false),
                // Empty UUID since the asset is not linked with any project for now
                UniqueAssetComponent(type = AssetType.CLOCK)
            )

        // Making panel entity child of clock entity
        clockPanel.setComponent(TransformParent(clock))
    }

    // Show or hide clock and clock children
    fun showClock(state: Boolean) {
        clock.setComponent(Visible(state))

        for (child in getChildren(clock)) {
            child.setComponent(Visible(state))
        }
    }

    // Show or hide speaker and speaker children
    fun showSpeaker(state: Boolean) {
        speaker.setComponent(Visible(state))

        for (child in getChildren(speaker)) {
            child.setComponent(Visible(state))
        }
    }

    // Delete button (trash can) to show when an element has been selected
    private fun createDeleteButton() {
        deleteButton =
            Entity.create(
                Mesh(Uri.parse("mesh://box")),
                Box(Vector3(-0.02f, -0.02f, 0f), Vector3(0.02f, 0.02f, 0f)),
                Transform(Pose(Vector3(0f))),
                IsdkPanelDimensions(Vector2(0.04f, 0.04f)),
                Material().apply {
                    baseTextureAndroidResourceId = R.drawable.delete
                    alphaMode = 1
                    unlit = true
                },
            )

        addOnSelectListener(
            deleteButton,
            fun() {
                deleteObject(currentObjectSelected, true)
            })
    }

    // Creation of scene elements
    fun createSceneElements() {

        logo =
            Entity.create(
                Mesh(mesh = Uri.parse("focus_logo.glb")),
                Scale(Vector3(0.075f)),
                Visible(false),
                Transform(Pose(Vector3(0f))),
                IsdkGrabbable(enabled = false, billboardOrientation = Vector3(0f, 180f, 0f)),
            )

        environment =
            Entity.create(
                Mesh(mesh = Uri.parse(environments[0])),
                Visible(false),
            )

        createSpeaker()
        createClock()
        createDeleteButton()
    }

    // Skybox is created after the app is initialized to improve performance. More info in GeneralSystem.kt
    fun createSkybox(res: Int) {
        skybox =
            Entity.create(
                Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
                Material().apply {
                    baseTextureAndroidResourceId = res
                    unlit = true // Prevent scene lighting from affecting the skybox
                },
                Visible(false),
                Transform(Pose(Vector3(0f))))
    }

    // Init app is called after app logo has been showed
    fun initApp() {
        appStarted = true
        logo.destroy()
        placeInFront(PanelManager.instance.homePanel)
        PanelManager.instance.homePanel.setComponent(Visible(true))
    }

    private fun setPassthrough(state: Boolean) {
        passthroughEnabled = state
        scene.enablePassthrough(state)
    }

    // Play ambient audio and save its state in database
    private fun playAmbientSound() {
        speakerIsOn = true

        // Playing spatial sound in loop
        ambientSoundPlayer.play(speaker.getComponent<Transform>().transform.t, 0.2f, true)

        // Change texture to speaker state image
        speakerState.setComponent(
            Material().apply {
                baseTextureAndroidResourceId = R.drawable.speaker_on
                alphaMode = 1
            })
        // Project audio state updated in database
        DB.updateUniqueAsset(speaker.getComponent<UniqueAssetComponent>().uuid, state = speakerIsOn)
        // Change texture to audio button state image in toolbar
        FocusViewModel.instance.setSpeakerIsOn(true)
    }

    // Stop ambient audio and save its state in database
    private fun stopAmbientSound() {
        speakerIsOn = false
        ambientSoundPlayer.stop()

        // Change texture to speaker state image
        speakerState.setComponent(
            Material().apply {
                baseTextureAndroidResourceId = R.drawable.speaker_off
                alphaMode = 1
            })
        // Project audio state updated in database
        DB.updateUniqueAsset(speaker.getComponent<UniqueAssetComponent>().uuid, state = speakerIsOn)
        // Change texture to audio button state image in toolbar
        FocusViewModel.instance.setSpeakerIsOn(false)
    }

    // Play sound when a tool has been created
    fun playCreationSound(position: Vector3) {
        scene.playSound(createSound, position, 1f)
    }

    // Change environment model and skybox texture when different environment has been selected
    fun selectEnvironment(env: Int) {
        setPassthrough(false)
        setLighting(env)

        currentEnvironment = env
        // Change 3D model of environment entity
        environment.setComponent(Mesh(mesh = Uri.parse(environments[env])))
        environment.setComponent(Visible(true))

        // Change texture of skybox entity
        skybox.setComponent(
            Material().apply {
                baseTextureAndroidResourceId = skyboxes[env]
                unlit = true
            },
        )
        skybox.setComponent(Visible(true))
    }

    // Show passthrough and hide environment and skybox
    fun selectMRMode() {
        setPassthrough(true)
        environment.setComponent(Visible(false))
        skybox.setComponent(Visible(false))
    }

    fun SwitchAudio() {
        if (speakerIsOn) {
            stopAmbientSound()
        } else {
            playAmbientSound()
        }
    }

    companion object {
        lateinit var instance: WeakReference<ImmersiveActivity>

        fun getInstance(): ImmersiveActivity? =
            if (::instance.isInitialized) instance.get() else null
    }
}