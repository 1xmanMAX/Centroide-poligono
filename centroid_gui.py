import numpy as np
import matplotlib.pyplot as plt
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
import tkinter as tk
from tkinter import ttk, messagebox
import re

class CentroidCalculator:
    def __init__(self):
        pass
        
    def polygon_centroid(self, vertices):
        """Calculate the centroid of a polygon given its vertices."""
        vertices = np.array(vertices)
        n = len(vertices)
        
        if n < 3:
            raise ValueError("A polygon must have at least 3 vertices")
            
        # Initialize area and centroid components
        area = 0
        centroid_x = 0
        centroid_y = 0
        
        # Using the correct Shoelace formula for area and centroid calculation
        for i in range(n):
            j = (i + 1) % n
            cross_product = vertices[i, 0] * vertices[j, 1] - vertices[j, 0] * vertices[i, 1]
            area += cross_product
            centroid_x += (vertices[i, 0] + vertices[j, 0]) * cross_product
            centroid_y += (vertices[i, 1] + vertices[j, 1]) * cross_product
        
        # Store the sign of the area before taking absolute value
        sign = 1 if area >= 0 else -1
        
        # Finalize the calculations
        area_abs = abs(area) / 2.0
        
        # Check for zero area to avoid division by zero
        if area_abs < 1e-10:
            raise ValueError("The polygon has zero area. Check if vertices are collinear.")
            
        # Apply the sign correction to ensure centroid is inside the polygon
        centroid_x = abs(centroid_x) / (6.0 * area_abs) * sign
        centroid_y = abs(centroid_y) / (6.0 * area_abs) * sign
        
        return np.array([centroid_x, centroid_y]), area_abs

class CentroidGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("Polygon Centroid Calculator")
        self.root.geometry("800x600")
        
        self.calculator = CentroidCalculator()
        self.vertices = []
        
        # Create main frame
        main_frame = ttk.Frame(root, padding="10")
        main_frame.pack(fill=tk.BOTH, expand=True)
        
        # Create input frame
        input_frame = ttk.LabelFrame(main_frame, text="Add Vertex", padding="10")
        input_frame.pack(fill=tk.X, pady=5)
        
        # X coordinate input
        ttk.Label(input_frame, text="X:").grid(row=0, column=0, padx=5, pady=5)
        self.x_entry = ttk.Entry(input_frame, width=10)
        self.x_entry.grid(row=0, column=1, padx=5, pady=5)
        
        # Y coordinate input
        ttk.Label(input_frame, text="Y:").grid(row=0, column=2, padx=5, pady=5)
        self.y_entry = ttk.Entry(input_frame, width=10)
        self.y_entry.grid(row=0, column=3, padx=5, pady=5)
        
        # Add vertex button
        add_button = ttk.Button(input_frame, text="Add Vertex", command=self.add_vertex)
        add_button.grid(row=0, column=4, padx=5, pady=5)
        
        # Calculate button
        calc_button = ttk.Button(input_frame, text="Calculate Centroid", command=self.calculate_centroid)
        calc_button.grid(row=0, column=5, padx=5, pady=5)
        
        # Clear button
        clear_button = ttk.Button(input_frame, text="Clear All", command=self.clear_all)
        clear_button.grid(row=0, column=6, padx=5, pady=5)
        
        # Add paste coordinates frame
        paste_frame = ttk.LabelFrame(main_frame, text="Paste Multiple Coordinates", padding="10")
        paste_frame.pack(fill=tk.X, pady=5)
        
        # Paste coordinates text area
        self.paste_text = tk.Text(paste_frame, height=4, width=50)
        self.paste_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=5, pady=5)
        paste_scrollbar = ttk.Scrollbar(paste_frame, orient=tk.VERTICAL, command=self.paste_text.yview)
        paste_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.paste_text.config(yscrollcommand=paste_scrollbar.set)
        
        # Paste coordinates button
        paste_button = ttk.Button(paste_frame, text="Add Coordinates", command=self.add_pasted_coordinates)
        paste_button.pack(side=tk.RIGHT, padx=5, pady=5)
        
        # Add help text
        help_text = ttk.Label(paste_frame, text="Format: (x, y), (x, y), ...")
        help_text.pack(side=tk.BOTTOM, padx=5)
        
        # Vertices list frame
        vertices_frame = ttk.LabelFrame(main_frame, text="Vertices", padding="10")
        vertices_frame.pack(fill=tk.BOTH, expand=True, pady=5)
        
        # Vertices listbox
        self.vertices_listbox = tk.Listbox(vertices_frame)
        self.vertices_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        
        # Scrollbar for listbox
        scrollbar = ttk.Scrollbar(vertices_frame, orient=tk.VERTICAL, command=self.vertices_listbox.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.vertices_listbox.config(yscrollcommand=scrollbar.set)
        
        # Result frame
        result_frame = ttk.LabelFrame(main_frame, text="Result", padding="10")
        result_frame.pack(fill=tk.X, pady=5)
        
        # Result labels
        self.result_label = ttk.Label(result_frame, text="Centroid: Not calculated")
        self.result_label.pack(pady=2)
        
        self.area_label = ttk.Label(result_frame, text="Area: Not calculated")
        self.area_label.pack(pady=2)
        
        # Plot frame
        plot_frame = ttk.LabelFrame(main_frame, text="Visualization", padding="10")
        plot_frame.pack(fill=tk.BOTH, expand=True, pady=5)
        
        # Create matplotlib figure
        self.figure = plt.Figure(figsize=(5, 4), dpi=100)
        self.ax = self.figure.add_subplot(111)
        self.ax.set_xlabel('X')
        self.ax.set_ylabel('Y')
        self.ax.grid(True)
        
        # Create canvas
        self.canvas = FigureCanvasTkAgg(self.figure, plot_frame)
        self.canvas.get_tk_widget().pack(fill=tk.BOTH, expand=True)
        
        # Set focus to x_entry
        self.x_entry.focus_set()
        
        # Bind Enter key to add_vertex
        self.root.bind('<Return>', lambda event: self.add_vertex())
    
    def add_pasted_coordinates(self):
        """Parse and add coordinates from the paste text area."""
        text = self.paste_text.get("1.0", tk.END).strip()
        if not text:
            return
            
        # Regular expression to match coordinates in format (x, y)
        pattern = r'\((-?\d+\.?\d*),\s*(-?\d+\.?\d*)\)'
        matches = re.findall(pattern, text)
        
        if not matches:
            messagebox.showerror("Format Error", "No valid coordinates found. Use format: (x, y), (x, y), ...")
            return
            
        added_count = 0
        for match in matches:
            try:
                x = float(match[0])
                y = float(match[1])
                
                self.vertices.append([x, y])
                self.vertices_listbox.insert(tk.END, f"Vertex {len(self.vertices)}: ({x}, {y})")
                added_count += 1
            except ValueError:
                continue
                
        # Clear the paste text area
        self.paste_text.delete("1.0", tk.END)
        
        # Update the plot
        self.update_plot()
        
        messagebox.showinfo("Coordinates Added", f"Successfully added {added_count} coordinates.")
        
    def add_vertex(self):
        try:
            x = float(self.x_entry.get())
            y = float(self.y_entry.get())
            
            self.vertices.append([x, y])
            self.vertices_listbox.insert(tk.END, f"Vertex {len(self.vertices)}: ({x}, {y})")
            
            # Clear entries
            self.x_entry.delete(0, tk.END)
            self.y_entry.delete(0, tk.END)
            self.x_entry.focus_set()
            
            # Update plot
            self.update_plot()
            
        except ValueError:
            messagebox.showerror("Input Error", "Please enter valid numeric coordinates.")
    
    def calculate_centroid(self):
        if len(self.vertices) < 3:
            messagebox.showerror("Error", "A polygon must have at least 3 vertices.")
            return
        
        try:
            centroid, area = self.calculator.polygon_centroid(self.vertices)
            self.result_label.config(text=f"Centroid: ({centroid[0]:.2f}, {centroid[1]:.2f})")
            self.area_label.config(text=f"Area: {area:.2f} square units")
            
            # Update plot with centroid
            self.update_plot(centroid)
            
        except Exception as e:
            messagebox.showerror("Calculation Error", str(e))
    
    def clear_all(self):
        self.vertices = []
        self.vertices_listbox.delete(0, tk.END)
        self.result_label.config(text="Centroid: Not calculated")
        self.area_label.config(text="Area: Not calculated")
        self.x_entry.delete(0, tk.END)
        self.y_entry.delete(0, tk.END)
        self.x_entry.focus_set()
        
        # Clear plot
        self.ax.clear()
        self.ax.set_xlabel('X')
        self.ax.set_ylabel('Y')
        self.ax.grid(True)
        self.canvas.draw()
    
    def update_plot(self, centroid=None):
        self.ax.clear()
        
        if self.vertices:
            vertices = np.array(self.vertices)
            
            # Plot vertices
            self.ax.plot(vertices[:, 0], vertices[:, 1], 'bo-', label='Polygon')
            
            # If there are at least 3 vertices, close the polygon
            if len(vertices) >= 3:
                vertices_closed = np.vstack([vertices, vertices[0]])
                self.ax.plot(vertices_closed[:, 0], vertices_closed[:, 1], 'b-')
                
                # Calculate and display area if we have a valid polygon
                try:
                    _, area = self.calculator.polygon_centroid(self.vertices)
                    self.ax.set_title(f"Area: {area:.2f} square units")
                except:
                    pass
            
            # Plot centroid if available
            if centroid is not None:
                self.ax.plot(centroid[0], centroid[1], 'ro', label='Centroid')
                self.ax.annotate(f'({centroid[0]:.2f}, {centroid[1]:.2f})', 
                                 (centroid[0], centroid[1]),
                                 textcoords="offset points",
                                 xytext=(0,10),
                                 ha='center')
            
            # Add vertex numbers
            for i, (x, y) in enumerate(vertices):
                self.ax.annotate(str(i+1), (x, y), textcoords="offset points", xytext=(5,5))
            
            self.ax.legend()
        
        self.ax.grid(True)
        self.ax.set_xlabel('X')
        self.ax.set_ylabel('Y')
        
        # Set equal aspect ratio
        self.ax.set_aspect('equal', 'datalim')
        
        self.canvas.draw()

if __name__ == "__main__":
    root = tk.Tk()
    app = CentroidGUI(root)
    root.mainloop()