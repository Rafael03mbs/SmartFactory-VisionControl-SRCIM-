# SmartFactory VisionControl

Cyber-Physical System (CPS) for automated industrial quality inspection, developed as part of the Systems and Real-Time Interconnected Computing (SRCIM) course at FCT NOVA.

Grade: 20 / 20

---

## Overview

This project implements an industrial automation pipeline that combines robotic simulation, multi-agent coordination, and real-time computer vision to inspect and sort products on a production line.

The system detects surface defects on trays using a trained YOLOv8 model and autonomously decides whether to accept, reject, or recover each item.

---

## Architecture

Three integrated components work together:

**1. Robotic Simulation (CoppeliaSim)**
- Simulates the conveyor belt, robotic arm, and product flow
- Communicates with the agent layer via the CoppeliaSim remote API

**2. Multi-Agent System (JADE)**
- Supervisor agent coordinates the production line state machine
- Inspection agent triggers vision analysis requests
- Recovery agent handles rejected items with autonomous re-routing logic

**3. Quality Inspection API (Python / FastAPI / YOLOv8)**
- REST endpoint receives image frames from the simulation
- Runs inference with a custom-trained YOLOv8 model (`best.pt`)
- Returns bounding boxes, defect classifications, and a pass/fail decision
- Exposes an HTML dashboard (`dashboard.html`) for real-time monitoring

---

## Tech Stack

| Component | Technology |
|---|---|
| Vision / Inference | Python, YOLOv8 (Ultralytics), FastAPI |
| Multi-Agent System | Java, JADE framework |
| Simulation | CoppeliaSim |
| Image Processing | Pillow, NumPy |
| Communication | REST (HTTP), CoppeliaSim Remote API |

---

## How to Run

**1. Start the Inspection API**

```bash
pip install fastapi uvicorn ultralytics pillow numpy
uvicorn api_server:app --reload
```

The dashboard is available at `http://localhost:8000`.

**2. Open the simulation**

Load the scene from `SRCIM_Lab2_Sim_Files_2026_corrigido/` in CoppeliaSim.

**3. Launch the JADE agents**

Follow the instructions in `SRCIM_Lab1_Template_Proj/` to start the agent platform and deploy the agents.

---

## Repository Structure

```
SmartFactory-VisionControl-SRCIM-/
├── api_server.py                        # FastAPI quality inspection server
├── best.pt                              # Trained YOLOv8 model weights
├── dashboard.html                       # Real-time monitoring interface
├── SRCIM_Lab1_Template_Proj/            # JADE multi-agent system
├── SRCIM_Lab2_Sim_Files_2026_corrigido/ # CoppeliaSim simulation scenes
├── archive_and_data/                    # Training data and experiment logs
└── images/                             # Sample inspection images
```

---

## Author

Rafael Martins Batista da Silva
MSc Electrical Engineering — FCT NOVA
[github.com/Rafael03mbs](https://github.com/Rafael03mbs)
