import io
from fastapi import FastAPI, UploadFile, File
from ultralytics import YOLO
from PIL import Image
import uvicorn
import cv2
import numpy as np

app = FastAPI(title="Quality Inspection API")

# Carregar o nosso cérebro recém-treinado
print("=" * 60)
print("🔍 A carregar modelo YOLO...")
try:
    model = YOLO("best.pt")
    class_names = model.names
    print(f"✅ Modelo carregado com sucesso!")
    print(f"   Ficheiro: best.pt")
    print(f"   Classes ({len(class_names)}): {class_names}")
    print(f"   Tipo de modelo: {model.task}")
    # Quick test to ensure model can run inference
    test_img = Image.new("RGB", (128, 128), color=(128, 128, 128))
    test_result = model(test_img, verbose=False)
    print(f"   Teste de inferência: OK ({len(test_result[0].boxes)} detecções no teste)")
except Exception as e:
    print(f"❌ ERRO ao carregar modelo YOLO: {e}")
    model = None
print(f"   Endpoint: POST http://localhost:8000/inspect")
print("=" * 60)


def detect_product_orientation(image: Image.Image) -> str:
    """
    Free-choice Feature (Option 2): Detect the product's orientation.
    
    Uses OpenCV contour analysis to determine if the product is in its
    default HORIZONTAL orientation or if it arrived VERTICAL (rotated ~90°).
    
    Method: Finds the largest contour in the image (the product/glue path),
    fits a minimum-area bounding rectangle, and checks its aspect ratio.
    
    Returns: "HORIZONTAL" (default) or "VERTICAL"
    """
    img_array = np.array(image)
    gray = cv2.cvtColor(img_array, cv2.COLOR_RGB2GRAY)
    
    # Apply Gaussian blur to reduce noise
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    
    # Adaptive threshold to isolate the product/glue path from the background
    thresh = cv2.adaptiveThreshold(
        blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY_INV, 31, 10
    )
    
    # Morphological operations to clean up the mask
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    cleaned = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel, iterations=2)
    
    # Find contours
    contours, _ = cv2.findContours(cleaned, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    if not contours:
        return "HORIZONTAL"  # Default if no contours found
    
    # Get the largest contour (the product)
    largest_contour = max(contours, key=cv2.contourArea)
    
    # Fit a minimum-area rotated rectangle
    rect = cv2.minAreaRect(largest_contour)
    rect_w, rect_h = rect[1]  # (width, height) of the rotated rectangle
    
    if rect_w == 0 or rect_h == 0:
        return "HORIZONTAL"
    
    # Determine orientation based on aspect ratio
    # If the bounding rect is taller than it is wide, the product is vertical
    aspect_ratio = max(rect_w, rect_h) / min(rect_w, rect_h)
    
    # The product has a clear rectangular shape; if the dominant axis is vertical, it's rotated
    if rect_w < rect_h:
        # The shorter side is horizontal → product is standing up (vertical)
        orientation = "VERTICAL"
    else:
        orientation = "HORIZONTAL"
    
    print(f"[ORIENTATION] Rect: {rect_w:.0f}x{rect_h:.0f}, "
          f"Aspect ratio: {aspect_ratio:.2f}, Result: {orientation}")
    
    return orientation


def classify_defect_region(defects_info: list, img_w: float, img_h: float, 
                            orientation: str) -> str:
    """
    Free-choice Feature (Option 2): Classify defect region relative to
    the product's DEFAULT (horizontal) orientation.
    
    When the product is in its default HORIZONTAL orientation:
      - Use y_center: top half → "TOP", bottom half → "BOTTOM"
    
    When the product arrives VERTICAL (rotated ~90°):
      - The "top" of the default orientation is now on one side
      - Use x_center instead: left half → "TOP", right half → "BOTTOM"
      
    This ensures the correct glue station is chosen regardless of how
    the product arrives at the quality control station.
    
    TOP    → GlueStation1 (recovery with sk_g_b)
    BOTTOM → GlueStation2 (recovery with sk_g_c)
    """
    if orientation == "HORIZONTAL":
        # Standard case: use y position
        avg_y = sum(d["y_center"] for d in defects_info) / len(defects_info)
        region = "TOP" if avg_y < img_h / 2 else "BOTTOM"
        print(f"[REGION] Horizontal: avg_y={avg_y:.1f}, mid={img_h/2:.1f} → {region}")
    else:
        # Vertical: the product is rotated ~90°, so map x → y
        # Left side in vertical = Top in default orientation
        avg_x = sum(d["x_center"] for d in defects_info) / len(defects_info)
        region = "TOP" if avg_x < img_w / 2 else "BOTTOM"
        print(f"[REGION] Vertical: avg_x={avg_x:.1f}, mid={img_w/2:.1f} → {region}")
    
    return region


@app.post("/inspect")
async def inspect_product(file: UploadFile = File(...)):
    try:
        # Ler a imagem que o Java (Simulador) nos enviou
        image_bytes = await file.read()
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        
        # O YOLO entra em ação e avalia a imagem
        results = model(image, conf=0.1)
        
        # Analisar o que o YOLO viu na imagem
        boxes = results[0].boxes
        
        # Se não encontrou nenhum defeito → produto OK
        if len(boxes) == 0:
            return {
                "status": "OK", 
                "message": "Produto em perfeitas condições",
                "defect_region": "NONE",
                "orientation": detect_product_orientation(image)
            }
        
        # Extrair informação dos defeitos detetados
        defects_info = []
        for box in boxes.xywh:  # x-centro, y-centro, largura, altura
            x, y, w, h = box.tolist()
            defects_info.append({
                "x_center": float(x),
                "y_center": float(y),
                "width": float(w),
                "height": float(h)
            })
        
        img_w = float(image.width)
        img_h = float(image.height)
        
        # Free-choice Feature: Detect product orientation
        orientation = detect_product_orientation(image)
        
        # Classify defect region relative to default orientation
        defect_region = classify_defect_region(defects_info, img_w, img_h, orientation)
            
        return {
            "status": "NOK", 
            "message": f"Atenção: {len(boxes)} defeito(s) detetado(s)!",
            "image_width": img_w,
            "image_height": img_h,
            "orientation": orientation,
            "defect_region": defect_region,
            "defects": defects_info
        }
    except Exception as e:
        return {"error": str(e)}


if __name__ == "__main__":
    print("🚀 API de Inspeção a arrancar na porta 8000...")
    print("   Features: YOLO Inspection + Orientation Detection (Option 2)")
    uvicorn.run(app, host="0.0.0.0", port=8000)
