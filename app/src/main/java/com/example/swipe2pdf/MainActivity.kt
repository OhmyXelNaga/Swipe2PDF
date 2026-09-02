package com.example.swipe2pdf

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{SwipeApp()}}}}

@Composable fun SwipeApp(){
    val context=LocalContext.current;val activity=context as Activity;val store=remember{SettingsStore(context)};val persisted by store.config.collectAsState(initial=JobConfig());var c by remember(persisted){mutableStateOf(persisted)};val scope=rememberCoroutineScope();val captures by AppState.captures.collectAsStateWithLifecycle();val state by AppState.state.collectAsStateWithLifecycle();val msg by AppState.message.collectAsStateWithLifecycle();var tab by remember{mutableIntStateOf(if(captures.isEmpty())0 else 2)};var projectionReady by remember{mutableStateOf(false)};var projectionResult by remember{mutableStateOf<Pair<Int,Intent>?>(null)}
    val projection=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){if(it.resultCode==Activity.RESULT_OK&&it.data!=null){projectionReady=true;projectionResult=it.resultCode to it.data!!}else AppState.message.value="화면 캡처 권한이 허용되지 않았습니다."}
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(captures.size,state){if(captures.isNotEmpty()&&(state==JobState.Processing||state==JobState.Error))tab=2}
    Scaffold(topBar={TopAppBar(title={Text("Swipe2PDF")})}){pad->Column(Modifier.padding(pad).fillMaxSize()){TabRow(tab){listOf("권한","작업 설정","결과 검토").forEachIndexed{i,s->Tab(tab==i,{tab=i},text={Text(s)})}};msg?.let{AssistChip({AppState.message.value=null},label={Text(it)})};when(tab){0->Permissions(projectionReady,{val m=context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager;projection.launch(m.createScreenCaptureIntent())},notification);1->ConfigScreen(c,{c=it;scope.launch{store.save(it)}},{
            val pr=projectionResult;if(pr==null){AppState.message.value="먼저 권한 화면에서 화면 캡처에 동의해 주세요.";tab=0}else if(!Settings.canDrawOverlays(context)){AppState.message.value="다른 앱 위에 표시 권한이 필요합니다.";tab=0}else if(SwipeAccessibilityService.instance==null){AppState.message.value="접근성 서비스를 켜 주세요.";tab=0}else{scope.launch{store.save(c)};ContextCompat.startForegroundService(context,Intent(context,CaptureService::class.java).putExtra(CaptureService.EXTRA_RESULT,pr.first).putExtra(CaptureService.EXTRA_DATA,pr.second).putExtra(CaptureService.EXTRA_CONFIG,ConfigCodec.encode(c)));projectionReady=false;projectionResult=null;AppState.message.value="플로팅 컨트롤러가 켜졌습니다. 대상 앱으로 이동해 시작 상태를 확인하세요."}});else->ReviewScreen(c,captures,{AppState.captures.value=it})}}
    }}
}

@Composable private fun Permissions(projectionReady:Boolean,onProjection:()->Unit,notification:androidx.activity.result.ActivityResultLauncher<String>){val context=LocalContext.current;var tick by remember{mutableIntStateOf(0)};val access=SwipeAccessibilityService.instance!=null;val overlay=Settings.canDrawOverlays(context);val notif=Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("필수 권한 설정",style=MaterialTheme.typography.headlineSmall);PermissionRow("접근성 서비스",access,"오른쪽에서 왼쪽으로 스와이프 동작을 수행합니다."){context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));tick++};PermissionRow("화면 캡처 동의",projectionReady,"Android 시스템 확인창에서 현재 화면 캡처를 허용합니다. 새 작업마다 다시 동의해야 할 수 있습니다.",onProjection);PermissionRow("알림",notif,"캡처 중 필수 상시 알림을 표시합니다."){if(Build.VERSION.SDK_INT>=33)notification.launch(Manifest.permission.POST_NOTIFICATIONS)};PermissionRow("다른 앱 위에 표시",overlay,"대상 앱에서도 시작·일시정지·중지 컨트롤러를 표시합니다."){context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")));tick++};Button({tick++}){Text("권한 상태 새로고침")}}
}
@Composable private fun PermissionRow(title:String,ok:Boolean,desc:String,action:()->Unit){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("$title · ${if(ok)"허용됨" else "필요함"}",fontWeight=FontWeight.Bold,color=if(ok)Color(0xff19794b)else MaterialTheme.colorScheme.error);Text(desc);Button(action){Text(if(ok)"설정 열기" else "허용하기")}}}}

@Composable private fun ConfigScreen(c:JobConfig,onChange:(JobConfig)->Unit,onEnable:()->Unit){Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("캡처 설정",style=MaterialTheme.typography.headlineSmall);NumberField("총 출력 페이지 (1~999)",c.pages.toString()){it.toIntOrNull()?.coerceIn(1,999)?.let{v->onChange(c.copy(pages=v))}};PercentSlider("스와이프 시작 X",c.startX){onChange(c.copy(startX=it))};PercentSlider("스와이프 끝 X",c.endX){onChange(c.copy(endX=it))};PercentSlider("스와이프 Y",c.y){onChange(c.copy(y=it))};NumberField("제스처 시간(ms)",c.gestureMs.toString()){it.toLongOrNull()?.coerceIn(100,3000)?.let{v->onChange(c.copy(gestureMs=v))}};NumberField("페이지 전환 대기(ms)",c.delayMs.toString()){it.toLongOrNull()?.coerceIn(100,10000)?.let{v->onChange(c.copy(delayMs=v))}};Text("공통 자르기 영역",fontWeight=FontWeight.Bold);Box(Modifier.fillMaxWidth().aspectRatio(.55f).background(Color(0xffdddddd))){Box(Modifier.fillMaxWidth(c.crop.right-c.crop.left).fillMaxHeight(c.crop.bottom-c.crop.top).offset(x=(c.crop.left*300).dp,y=(c.crop.top*300).dp).border(3.dp,Color.Red))};PercentSlider("위쪽",c.crop.top,0f,c.crop.bottom-.02f){onChange(c.copy(crop=c.crop.copy(top=it)))};PercentSlider("아래쪽",c.crop.bottom,c.crop.top+.02f,1f){onChange(c.copy(crop=c.crop.copy(bottom=it)))};PercentSlider("왼쪽",c.crop.left,0f,c.crop.right-.02f){onChange(c.copy(crop=c.crop.copy(left=it)))};PercentSlider("오른쪽",c.crop.right,c.crop.left+.02f,1f){onChange(c.copy(crop=c.crop.copy(right=it)))};OutlinedTextField(c.filename,{onChange(c.copy(filename=it))},label={Text("출력 파일 이름")},modifier=Modifier.fillMaxWidth());Text("첫 화면을 먼저 캡처하며 ${c.pages}장 촬영 시 스와이프는 ${CapturePlan.swipesFor(c.pages)}회입니다.");Button(onEnable,Modifier.fillMaxWidth()){Text("플로팅 컨트롤러 켜기")}}
}
@Composable private fun NumberField(label:String,value:String,onValue:(String)->Unit)=OutlinedTextField(value,onValue,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())
@Composable private fun PercentSlider(label:String,value:Float,min:Float=0f,max:Float=1f,onValue:(Float)->Unit){Column{Text("$label: ${(value*100).toInt()}%");Slider(value,onValue,valueRange=min..max)}}

@Composable private fun ReviewScreen(c:JobConfig,files:List<File>,setFiles:(List<File>)->Unit){val context=LocalContext.current;val scope=rememberCoroutineScope();var selected by remember{mutableIntStateOf(0)};var duplicates by remember(files){mutableStateOf<Set<Int>>(emptySet())};val pdf by AppState.pdf.collectAsStateWithLifecycle();LaunchedEffect(files){duplicates=withContext(Dispatchers.IO){files.zipWithNext().mapIndexedNotNull{i,(a,b)->if(ImageAndPdf.similarity(a,b)>.985)i+1 else null}.toSet()}}
    Column(Modifier.fillMaxSize().padding(12.dp)){Text("촬영 결과 ${files.size}장",style=MaterialTheme.typography.headlineSmall);if(duplicates.isNotEmpty())Text("유사한 연속 이미지가 있습니다: ${duplicates.map{it+1}.joinToString()}쪽",color=MaterialTheme.colorScheme.error);LazyRow(Modifier.height(130.dp)){itemsIndexed(files){i,f->Card({selected=i},Modifier.width(90.dp).padding(3.dp)){Column{androidx.compose.foundation.Image(androidx.compose.ui.graphics.asImageBitmap(android.graphics.BitmapFactory.decodeFile(f.path)),null,Modifier.height(90.dp).fillMaxWidth(),contentScale=ContentScale.Crop);Text("${i+1}쪽${if(i in duplicates)" ⚠" else ""}")}}}};if(files.isNotEmpty()){val i=selected.coerceIn(files.indices);val f=files[i];androidx.compose.foundation.Image(androidx.compose.ui.graphics.asImageBitmap(android.graphics.BitmapFactory.decodeFile(f.path)),"선택 이미지",Modifier.weight(1f).fillMaxWidth(),contentScale=ContentScale.Fit);Row{Button({if(i>0){setFiles(reorder(files,i,i-1));selected=i-1}},enabled=i>0){Text("앞으로")};Button({if(i<files.lastIndex){setFiles(reorder(files,i,i+1));selected=i+1}},enabled=i<files.lastIndex){Text("뒤로")};Button({f.delete();setFiles(files.filterIndexed{n,_->n!=i});selected=(i-1).coerceAtLeast(0)}){Text("삭제")}};Button({scope.launch{AppState.state.value=JobState.Processing;runCatching{withContext(Dispatchers.IO){ImageAndPdf.createPdf(context,files,c.crop,c.filename)}}.onSuccess{AppState.pdf.value=it;AppState.state.value=JobState.Completed;files.forEach(File::delete);setFiles(emptyList())}.onFailure{AppState.state.value=JobState.Error;AppState.message.value="PDF 생성 실패: ${it.message}"}}},Modifier.fillMaxWidth()){Text("PDF 만들기")};OutlinedButton({files.forEach(File::delete);setFiles(emptyList())},Modifier.fillMaxWidth()){Text("전체 폐기")}}
        pdf?.let{(uri,name)->Text("저장 완료: 다운로드/Swipe2PDF/$name");Row{Button({runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{AppState.message.value="PDF를 열 수 있는 앱을 찾지 못했습니다."}}){Text("PDF 열기")};Button({context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"PDF 공유"))}){Text("공유")};Button({context.startActivity(Intent(Intent.ACTION_VIEW,MediaStore.Downloads.EXTERNAL_CONTENT_URI))}){Text("저장 위치 보기")}}}}
}
