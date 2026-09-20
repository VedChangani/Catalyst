package in.vedchangani.billingsoftware.config;

import in.vedchangani.billingsoftware.util.UploadUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    // Same directory the item/category services write uploads to (app.uploads.dir).
    @Value("${app.uploads.dir}")
    private String uploadsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadDir = UploadUrls.directory(uploadsDir).toString();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:"+uploadDir+"/");
    }
}
