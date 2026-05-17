import io
import os
import base64
from fastapi import FastAPI, UploadFile, File, Query
from fastapi.responses import HTMLResponse
from ultralytics import YOLO
from PIL import Image, ImageDraw, ImageFont
import uvicorn
import numpy as np

app = FastAPI(title="Quality Inspection API")

DETECTION_CONFIDENCE = 0.10
TRAY_THRESHOLD = 150
TRAY_PADDING = 12

def image_to_base64(img: Image.Image) -> str:
    buffered = io.BytesIO()
    img.save(buffered, format="JPEG")
    return base64.b64encode(buffered.getvalue()).decode("utf-8")

def draw_tray_bbox(image: Image.Image, bbox: list) -> Image.Image:
    annotated = image.copy().convert("RGBA")
    draw = ImageDraw.Draw(annotated)
    left, top, right, bottom = bbox
    
    # 1. Draw a prominent yellow outline for the tray
    draw.rectangle([left, top, right, bottom], outline=(255, 215, 0, 255), width=6)
    
    # 2. Draw a semi-transparent filled rectangle inside
    overlay = Image.new("RGBA", annotated.size, (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    overlay_draw.rectangle([left, top, right, bottom], fill=(255, 215, 0, 25))
    annotated = Image.alpha_composite(annotated, overlay)
    
    # 3. Add text label
    draw = ImageDraw.Draw(annotated)
    try:
        font = ImageFont.truetype("arial.ttf", 16)
    except IOError:
        font = ImageFont.load_default()
        
    text = f"TABULEIRO DETETADO {bbox}"
    # Draw dark background for the text to make it readable
    text_w, text_h = 240, 22
    draw.rectangle([left, max(0, top - 25), left + text_w, max(0, top)], fill=(255, 215, 0, 255))
    draw.text((left + 5, max(0, top - 22)), text, fill=(0, 0, 0, 255), font=font)
    
    return annotated.convert("RGB")

def draw_cropped_annotations(cropped_image: Image.Image, defects: list, orientation: str, defect_region: str) -> Image.Image:
    annotated = cropped_image.copy().convert("RGBA")
    w, h = annotated.size
    overlay = Image.new("RGBA", annotated.size, (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    
    try:
        font_large = ImageFont.truetype("arial.ttf", 14)
        font_small = ImageFont.truetype("arial.ttf", 11)
    except IOError:
        font_large = ImageFont.load_default()
        font_small = ImageFont.load_default()
        
    # 1. Draw division line and shade regions
    if orientation == "HORIZONTAL":
        mid_x = w / 2
        # Vertical divider line
        overlay_draw.line([(mid_x, 0), (mid_x, h)], fill=(255, 255, 255, 180), width=4)
        
        # Shade the active defect region in red, other in green
        if len(defects) > 0:
            if defect_region == "TOP":
                overlay_draw.rectangle([0, 0, mid_x, h], fill=(255, 0, 0, 45))  # Active (Defect)
                overlay_draw.rectangle([mid_x, 0, w, h], fill=(0, 255, 0, 20))  # Safe (OK)
            elif defect_region == "BOTTOM":
                overlay_draw.rectangle([0, 0, mid_x, h], fill=(0, 255, 0, 20))  # Safe (OK)
                overlay_draw.rectangle([mid_x, 0, w, h], fill=(255, 0, 0, 45))  # Active (Defect)
        else:
            # Entire product OK
            overlay_draw.rectangle([0, 0, w, h], fill=(0, 255, 0, 20))
            
        # Draw labels
        overlay_draw.rectangle([10, 10, 280, 32], fill=(0, 0, 0, 160))
        overlay_draw.text((15, 13), "ZONA SUPERIOR (TOP) - Station 1", fill=(255, 255, 255, 255), font=font_large)
        
        overlay_draw.rectangle([mid_x + 10, 10, mid_x + 300, 32], fill=(0, 0, 0, 160))
        overlay_draw.text((mid_x + 15, 13), "ZONA INFERIOR (BOTTOM) - Station 2", fill=(255, 255, 255, 255), font=font_large)
        
    else:
        mid_y = h / 2
        # Horizontal divider line
        overlay_draw.line([(0, mid_y), (w, mid_y)], fill=(255, 255, 255, 180), width=4)
        
        if len(defects) > 0:
            if defect_region == "TOP":
                overlay_draw.rectangle([0, 0, w, mid_y], fill=(255, 0, 0, 45))  # Active (Defect)
                overlay_draw.rectangle([0, mid_y, w, h], fill=(0, 255, 0, 20))  # Safe (OK)
            elif defect_region == "BOTTOM":
                overlay_draw.rectangle([0, 0, w, mid_y], fill=(0, 255, 0, 20))  # Safe (OK)
                overlay_draw.rectangle([0, mid_y, w, h], fill=(255, 0, 0, 45))  # Active (Defect)
        else:
            # Entire product OK
            overlay_draw.rectangle([0, 0, w, h], fill=(0, 255, 0, 20))
            
        # Draw labels
        overlay_draw.rectangle([10, 10, 280, 32], fill=(0, 0, 0, 160))
        overlay_draw.text((15, 13), "ZONA SUPERIOR (TOP) - Station 1", fill=(255, 255, 255, 255), font=font_large)
        
        overlay_draw.rectangle([10, mid_y + 10, 300, mid_y + 32], fill=(0, 0, 0, 160))
        overlay_draw.text((15, mid_y + 13), "ZONA INFERIOR (BOTTOM) - Station 2", fill=(255, 255, 255, 255), font=font_large)
        
    # Apply overlays
    annotated = Image.alpha_composite(annotated, overlay)
    
    # 2. Draw YOLO bounding boxes for defects
    draw = ImageDraw.Draw(annotated)
    for d in defects:
        cx, cy, dw, dh = d["x_center"], d["y_center"], d["width"], d["height"]
        left = cx - dw / 2
        top = cy - dh / 2
        right = cx + dw / 2
        bottom = cy + dh / 2
        
        # Draw bounding box (red with solid border)
        draw.rectangle([left, top, right, bottom], outline=(255, 30, 30, 255), width=3)
        
        # Label class + confidence
        label_text = f"{d.get('class_name', 'defeito')} ({d.get('confidence', 0.0)*100:.1f}%)"
        
        # Draw text background
        draw.rectangle([left, max(0, top - 18), left + 130, max(0, top)], fill=(255, 30, 30, 255))
        draw.text((left + 4, max(0, top - 16)), label_text, fill=(255, 255, 255, 255), font=font_small)
        
        # Draw center point
        draw.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=(255, 255, 0, 255), outline=(0, 0, 0, 255))
        
    return annotated.convert("RGB")


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


def crop_tray(image: Image.Image):
    """
    The model was trained with tray/product images, not the full station view.
    Detect the bright tray region and crop the photo before sending it to YOLO.
    """
    arr = np.array(image)
    gray = arr.mean(axis=2)
    mask = gray > TRAY_THRESHOLD

    ys, xs = np.where(mask)
    if len(xs) == 0 or len(ys) == 0:
        print("[TRAY] Could not detect tray; using full image")
        return image, {
            "bbox": [0, 0, image.width, image.height],
            "cropped": False,
            "threshold": TRAY_THRESHOLD,
        }

    left = max(0, int(xs.min()) - TRAY_PADDING)
    top = max(0, int(ys.min()) - TRAY_PADDING)
    right = min(image.width, int(xs.max()) + TRAY_PADDING + 1)
    bottom = min(image.height, int(ys.max()) + TRAY_PADDING + 1)

    crop = image.crop((left, top, right, bottom))
    print(f"[TRAY] Cropped tray bbox=({left},{top},{right},{bottom}) "
          f"size={crop.width}x{crop.height}")

    return crop, {
        "bbox": [left, top, right, bottom],
        "cropped": True,
        "threshold": TRAY_THRESHOLD,
    }


def detect_tray_orientation(tray_image: Image.Image) -> str:
    if tray_image.width > tray_image.height * 1.15:
        orientation = "HORIZONTAL"
    elif tray_image.height > tray_image.width * 1.15:
        orientation = "VERTICAL"
    else:
        orientation = "SQUARE"

    print(f"[ORIENTATION] Tray size: {tray_image.width}x{tray_image.height}, "
          f"Result: {orientation}")
    return orientation


def classify_defect_region(defects_info: list, img_w: float, img_h: float, 
                            orientation: str) -> str:
    """
    Free-choice Feature (Option 2): Classify defect region relative to
    the product's DEFAULT (horizontal) orientation.
    
    The examples from the QC stations show a vertical tray, but the same tray can
    arrive horizontal. For vertical trays, TOP/BOTTOM maps directly to y. For a
    horizontal tray, the old top/bottom direction is now left/right in the image.
      
    This ensures the correct glue station is chosen regardless of how
    the product arrives at the quality control station.
    
    TOP    → GlueStation1 (recovery with sk_g_b)
    BOTTOM → GlueStation2 (recovery with sk_g_c)
    """
    if orientation == "HORIZONTAL":
        avg_x = sum(d["x_center"] for d in defects_info) / len(defects_info)
        region = "TOP" if avg_x < img_w / 2 else "BOTTOM"
        print(f"[REGION] Horizontal tray: avg_x={avg_x:.1f}, mid={img_w/2:.1f} → {region}")
    else:
        avg_y = sum(d["y_center"] for d in defects_info) / len(defects_info)
        region = "TOP" if avg_y < img_h / 2 else "BOTTOM"
        print(f"[REGION] Vertical tray: avg_y={avg_y:.1f}, mid={img_h/2:.1f} → {region}")
    
    return region


def extract_defects(boxes) -> list:
    defects_info = []
    for i, box in enumerate(boxes.xywh):
        x, y, w, h = box.tolist()
        defect = {
            "x_center": float(x),
            "y_center": float(y),
            "width": float(w),
            "height": float(h),
        }

        if getattr(boxes, "conf", None) is not None:
            defect["confidence"] = float(boxes.conf[i])
        if getattr(boxes, "cls", None) is not None:
            class_id = int(boxes.cls[i])
            defect["class_id"] = class_id
            if isinstance(class_names, dict):
                defect["class_name"] = class_names.get(class_id, str(class_id))
            else:
                defect["class_name"] = str(class_names[class_id]) if class_id < len(class_names) else str(class_id)

        defects_info.append(defect)
    return defects_info


@app.post("/inspect")
async def inspect_product(file: UploadFile = File(...)):
    try:
        # Ler a imagem que o Java (Simulador) nos enviou
        image_bytes = await file.read()
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

        if model is None:
            return {"error": "YOLO model was not loaded"}

        tray_image, tray_info = crop_tray(image)
        orientation = detect_tray_orientation(tray_image)
        
        # O YOLO recebe só o tabuleiro, que é o domínio em que foi treinado.
        results = model(tray_image, conf=DETECTION_CONFIDENCE)
        
        # Analisar o que o YOLO viu na imagem
        boxes = results[0].boxes
        
        # Se não encontrou nenhum defeito → produto OK
        if len(boxes) == 0:
            return {
                "status": "OK", 
                "message": "Produto em perfeitas condições",
                "defect_region": "NONE",
                "orientation": orientation,
                "source_image_size": [image.width, image.height],
                "model_image_size": [tray_image.width, tray_image.height],
                "tray": tray_info,
            }
        
        # Extrair informação dos defeitos detetados
        defects_info = extract_defects(boxes)
        img_w = float(tray_image.width)
        img_h = float(tray_image.height)
        
        # Classify defect region relative to default orientation
        defect_region = classify_defect_region(defects_info, img_w, img_h, orientation)
            
        return {
            "status": "NOK", 
            "message": f"Atenção: {len(boxes)} defeito(s) detetado(s)!",
            "source_image_size": [image.width, image.height],
            "model_image_size": [tray_image.width, tray_image.height],
            "orientation": orientation,
            "defect_region": defect_region,
            "tray": tray_info,
            "defects": defects_info
        }
    except Exception as e:
        return {"error": str(e)}


if __name__ == "__main__":
    print("🚀 API de Inspeção a arrancar na porta 8000...")
    print("   Features: Tray crop + YOLO Inspection + Orientation Detection")
    uvicorn.run(app, host="0.0.0.0", port=8000)
