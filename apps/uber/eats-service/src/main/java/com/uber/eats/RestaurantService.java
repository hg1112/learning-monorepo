// apps/uber/eats-service/src/main/java/com/uber/eats/RestaurantService.java
package com.uber.eats;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RestaurantService {

    @Autowired
    private RestaurantRepository repository;

    @Autowired
    private AmazonS3 s3Client;

    @Value("${minio.bucket}")
    private String bucketName;

    public Restaurant create(Restaurant restaurant) {
        return repository.save(restaurant);
    }

    public List<Restaurant> getAll() {
        return repository.findAll();
    }

    public Optional<Restaurant> getById(String id) {
        return repository.findById(id);
    }

    public List<Restaurant> getByCuisine(String cuisine) {
        return repository.findByCuisine(cuisine);
    }

    // Upload an image to MinIO and return the public URL.
    // The returned URL is stored in Restaurant.imageUrl or MenuItem.imageUrl.
    // Pattern: app uploads once → stores URL → mobile clients download
    // directly from MinIO without going through this service. This prevents
    // the app server from becoming a bandwidth bottleneck.
    public String uploadImage(MultipartFile file) throws IOException {
        ensureBucketExists();

        String fileName = UUID.randomUUID() + "-" + file.getOriginalFilename();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(file.getContentType());
        metadata.setContentLength(file.getSize());

        s3Client.putObject(bucketName, fileName, file.getInputStream(), metadata);
        return s3Client.getUrl(bucketName, fileName).toString();
    }

    private void ensureBucketExists() {
        if (!s3Client.doesBucketExistV2(bucketName)) {
            s3Client.createBucket(bucketName);
        }
    }
}