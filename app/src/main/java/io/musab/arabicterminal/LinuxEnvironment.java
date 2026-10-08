package io.musab.arabicterminal;

import android.content.Context;
import android.os.Build;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Offline installation of an ARM64 Alpine rootfs bundled with the APK. */
public final class LinuxEnvironment {
    private LinuxEnvironment(){}
    public static File rootfs(Context c){return new File(c.getFilesDir(),"alpine-rootfs");}
    public static boolean installed(Context c){
        File root=rootfs(c);
        return new File(root,".arabicterminal-installed").isFile()
            && new File(root,"etc/alpine-release").isFile()
            && new File(root,"bin/busybox").exists();
    }
    public static boolean runtimeAvailable(Context c){
        if(!"arm64-v8a".equals(Build.SUPPORTED_ABIS[0]))return false;
        File libs=new File(c.getApplicationInfo().nativeLibraryDir);
        return new File(libs,"libproot.so").isFile() &&
            new File(libs,"libproot-loader.so").isFile();
    }
    public static void install(Context c)throws Exception {
        if(installed(c))return;
        if(!runtimeAvailable(c))throw new IOException("مكتبات PRoot ARM64 غير موجودة في التطبيق");
        File root=rootfs(c),stage=new File(c.getFilesDir(),"alpine-staging");
        if(stage.exists())remove(stage);
        if(!stage.mkdirs())throw new IOException("تعذر إنشاء مساحة التوزيعة");
        try{
            byte[] archive;
            try(InputStream in=c.getAssets().open("alpine-rootfs.tgz");
                ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){
                    if(out.size()+n>40_000_000)throw new IOException("حجم أرشيف Alpine غير متوقع");
                    out.write(buf,0,n);
                }
                archive=out.toByteArray();
            }
            byte[] expected=new byte[128];int count;
            try(InputStream in=c.getAssets().open("alpine-rootfs.sha256")){
                count=in.read(expected);
            }
            if(count<=0)throw new IOException("ملف التحقق من التوزيعة غير موجود");
            String checksum=new String(expected,0,count,java.nio.charset.StandardCharsets.US_ASCII).trim();
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(archive);
            StringBuilder actual=new StringBuilder();
            for(byte b:digest)actual.append(String.format(java.util.Locale.ROOT,"%02x",b&0xff));
            if(!actual.toString().equalsIgnoreCase(checksum))
                throw new IOException("فشل التحقق من بصمة Alpine");
            try(TarArchiveInputStream tar=new TarArchiveInputStream(
                new GZIPInputStream(new ByteArrayInputStream(archive)))){
                extract(tar,stage);
            }
            File home=new File(stage,"root"),tmp=new File(stage,"tmp");
            home.mkdirs();tmp.mkdirs();
            tmp.setReadable(true,false);tmp.setWritable(true,false);tmp.setExecutable(true,false);
            File etc=new File(stage,"etc");etc.mkdirs();
            File dns=new File(etc,"resolv.conf");
            if(!dns.exists())Files.write(dns.toPath(),
                "nameserver 1.1.1.1\nnameserver 8.8.8.8\n".getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII));
            if(root.exists())remove(root);
            if(!stage.renameTo(root))throw new IOException("تعذر تفعيل ملفات لينكس");
            Files.write(new File(root,".arabicterminal-installed").toPath(),
                "Alpine 3.24.2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception error){
            remove(stage);throw error;
        }
    }
    private static void extract(TarArchiveInputStream input,File folder)throws IOException {
        Path base=folder.getCanonicalFile().toPath();
        TarArchiveEntry e;
        long total=0;int entries=0;
        List<TarArchiveEntry> links=new ArrayList<>();
        while((e=input.getNextTarEntry())!=null){
            if(++entries>100_000)throw new IOException("عدد ملفات التوزيعة كبير جدًا");
            String name=e.getName().replace('\\','/');
            if(name.startsWith("/")||name.indexOf('\0')>=0)throw new IOException("مسار غير آمن");
            Path relative=Paths.get(name).normalize();
            if(relative.isAbsolute()||relative.startsWith(".."))throw new IOException("مسار خارج التوزيعة");
            Path item=base.resolve(relative).normalize();
            if(!item.startsWith(base))throw new IOException("مسار خارج التوزيعة");
            if(e.isSymbolicLink()||e.isLink()){links.add(e);continue;}
            Path parent=item.getParent();
            if(parent!=null && !parent.toFile().isDirectory() && !parent.toFile().mkdirs())
                throw new IOException("تعذر إنشاء المجلد");
            if(e.isDirectory()){Files.createDirectories(item);continue;}
            if(!e.isFile())continue;
            long size=e.getSize();
            if(size<0||size>100_000_000||(total+=size)>750_000_000)
                throw new IOException("تجاوزت التوزيعة حدود الحجم");
            try(FileOutputStream output=new FileOutputStream(item.toFile())){
                byte[] buffer=new byte[8192];
                while(size>0){
                    int n=input.read(buffer,0,(int)Math.min(size,buffer.length));
                    if(n<0)throw new IOException("أرشيف Alpine ناقص");
                    output.write(buffer,0,n);size-=n;
                }
            }
            if((e.getMode()&0111)!=0)item.toFile().setExecutable(true,false);
            if((e.getMode()&0004)!=0)item.toFile().setReadable(true,false);
        }
        for(TarArchiveEntry link:links){
            Path relative=Paths.get(link.getName()).normalize();
            if(relative.isAbsolute()||relative.startsWith(".."))continue;
            Path item=base.resolve(relative).normalize();
            if(!item.startsWith(base))continue;
            Path parent=item.getParent();
            if(parent!=null)Files.createDirectories(parent);
            Path dest=Paths.get(link.getLinkName());
            if(link.isSymbolicLink()){
                if(!dest.isAbsolute()&&!parent.resolve(dest).normalize().startsWith(base))continue;
                try{Files.createSymbolicLink(item,dest);}
                catch(java.nio.file.FileAlreadyExistsException ignored){}
            }else if(link.isLink()&&!dest.isAbsolute()){
                Path linkTarget=base.resolve(dest).normalize();
                if(linkTarget.startsWith(base)&&Files.isRegularFile(linkTarget))
                    try{Files.createLink(item,linkTarget);}
                    catch(java.nio.file.FileAlreadyExistsException ignored){}
            }
        }
    }
    private static void remove(File folder)throws IOException {
        if(!folder.exists())return;
        try(java.util.stream.Stream<Path> all=Files.walk(folder.toPath())){
            for(Path p:(Iterable<Path>)all.sorted(java.util.Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(p);
        }
    }
}
